package lyjew.com.lyclaw.action.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lyjew.com.lyclaw.action.tool.ToolResult;
import lyjew.com.lyclaw.action.tool.ToolSandbox;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class ToolSandboxImpl implements ToolSandbox {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_LENGTH = 10000;

    private final ExecutorService sandboxExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "sandbox-worker");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "calculator", "current_time", "web_search"
    );

    @Override
    public ToolResult execute(Tool tool, Map<String, Object> args, SandboxLevel level) {
        if (!healthy.get()) {
            return ToolResult.builder()
                    .toolName(tool.getName())
                    .success(false)
                    .errorMessage("沙箱不可用")
                    .durationMs(0)
                    .build();
        }

        long startTime = System.currentTimeMillis();
        try {
            ToolCall toolCall = buildToolCall(tool.getName(), args);
            SandboxLevel effectiveLevel = level != null ? level : SandboxLevel.NONE;
            return switch (effectiveLevel) {
                case NONE -> executeNone(tool, toolCall, startTime);
                case READ_ONLY -> executeReadOnly(tool, toolCall, startTime);
                case RESTRICTED -> executeRestricted(tool, toolCall, startTime);
                case CONTAINER -> executeContainer(tool, toolCall, args, startTime);
                case ISOLATED -> executeIsolated(tool, toolCall, args, startTime);
            };
        } catch (Exception e) {
            log.error("沙箱执行异常: tool={}, level={}", tool.getName(), level, e);
            long elapsed = System.currentTimeMillis() - startTime;
            return ToolResult.builder()
                    .toolName(tool.getName())
                    .success(false)
                    .errorMessage("沙箱执行异常: " + e.getMessage())
                    .durationMs(elapsed)
                    .build();
        }
    }

    @Override
    public boolean isHealthy() {
        return healthy.get();
    }

    @Override
    public void destroy() {
        log.info("正在销毁沙箱...");
        healthy.set(false);
        sandboxExecutor.shutdown();
        try {
            if (!sandboxExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                sandboxExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            sandboxExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("沙箱已销毁");
    }

    private ToolResult executeNone(Tool tool, ToolCall toolCall, long startTime) {
        lyjew.com.lyclaw.tool.ToolResult innerResult = tool.execute(toolCall, null);
        return convertResult(tool.getName(), innerResult, startTime);
    }

    private ToolResult executeReadOnly(Tool tool, ToolCall toolCall, long startTime) {
        if (!READ_ONLY_TOOLS.contains(tool.getName())) {
            return ToolResult.builder()
                    .toolName(tool.getName())
                    .success(false)
                    .errorMessage("工具 " + tool.getName() + " 不允许在 READ_ONLY 沙箱中执行")
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        }
        lyjew.com.lyclaw.tool.ToolResult innerResult = tool.execute(toolCall, null);
        return convertResult(tool.getName(), innerResult, startTime);
    }

    private ToolResult executeRestricted(Tool tool, ToolCall toolCall, long startTime) {
        try {
            Future<lyjew.com.lyclaw.tool.ToolResult> future = sandboxExecutor.submit(() -> {
                Path tempDir = Files.createTempDirectory("lyclaw-sandbox-");
                try {
                    String originalDir = System.getProperty("user.dir");
                    System.setProperty("user.dir", tempDir.toString());
                    try {
                        return tool.execute(toolCall, null);
                    } finally {
                        System.setProperty("user.dir", originalDir);
                    }
                } finally {
                    try {
                        Files.walk(tempDir)
                                .sorted(java.util.Comparator.reverseOrder())
                                .forEach(p -> {
                                    try { Files.delete(p); } catch (IOException ignored) { }
                                });
                    } catch (IOException ignored) { }
                }
            });

            lyjew.com.lyclaw.tool.ToolResult innerResult =
                    future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return convertResult(tool.getName(), innerResult, startTime);
        } catch (TimeoutException e) {
            return ToolResult.builder()
                    .toolName(tool.getName())
                    .success(false)
                    .errorMessage("工具执行超时（" + DEFAULT_TIMEOUT_SECONDS + "秒）")
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        } catch (Exception e) {
            log.error("RESTRICTED 级别执行异常: tool={}", tool.getName(), e);
            return ToolResult.builder()
                    .toolName(tool.getName())
                    .success(false)
                    .errorMessage("受限沙箱执行异常: " + e.getMessage())
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    private ToolResult executeContainer(Tool tool, ToolCall toolCall,
                                         Map<String, Object> args, long startTime) {
        try {
            if ("command".equals(tool.getName()) && args.containsKey("command")) {
                return executeCommandInProcess(tool.getName(),
                        (String) args.get("command"), startTime);
            }
            log.debug("工具 {} 不支持进程隔离，降级到 RESTRICTED", tool.getName());
            return executeRestricted(tool, toolCall, startTime);
        } catch (Exception e) {
            log.error("CONTAINER 级别执行异常: tool={}", tool.getName(), e);
            return ToolResult.builder()
                    .toolName(tool.getName())
                    .success(false)
                    .errorMessage("容器沙箱执行异常: " + e.getMessage())
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    private ToolResult executeIsolated(Tool tool, ToolCall toolCall,
                                        Map<String, Object> args, long startTime) {
        try {
            if ("command".equals(tool.getName()) && args.containsKey("command")) {
                return executeCommandInProcess(tool.getName(),
                        (String) args.get("command"), startTime);
            }
            log.debug("工具 {} 不支持完全隔离，降级到 RESTRICTED", tool.getName());
            return executeRestricted(tool, toolCall, startTime);
        } catch (Exception e) {
            log.error("ISOLATED 级别执行异常: tool={}", tool.getName(), e);
            return ToolResult.builder()
                    .toolName(tool.getName())
                    .success(false)
                    .errorMessage("隔离沙箱执行异常: " + e.getMessage())
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    private ToolResult executeCommandInProcess(String toolName, String command, long startTime) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return ToolResult.builder()
                        .toolName(toolName)
                        .success(false)
                        .errorMessage("命令执行超时（" + DEFAULT_TIMEOUT_SECONDS + "秒）")
                        .durationMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() + line.length() + 1 > MAX_OUTPUT_LENGTH) {
                        output.append("\n...（输出已截断）");
                        break;
                    }
                    if (output.length() > 0) output.append("\n");
                    output.append(line);
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            int exitCode = process.exitValue();

            if (exitCode == 0) {
                return ToolResult.builder()
                        .toolName(toolName)
                        .success(true)
                        .output(output.length() > 0 ? output.toString() : "命令执行成功，无输出")
                        .durationMs(elapsed)
                        .build();
            } else {
                return ToolResult.builder()
                        .toolName(toolName)
                        .success(false)
                        .errorMessage("退出码 " + exitCode)
                        .output(output.toString())
                        .durationMs(elapsed)
                        .build();
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            return ToolResult.builder()
                    .toolName(toolName)
                    .success(false)
                    .errorMessage("进程执行异常: " + e.getMessage())
                    .durationMs(elapsed)
                    .build();
        }
    }

    private ToolCall buildToolCall(String toolName, Map<String, Object> args) {
        String arguments;
        try {
            arguments = objectMapper.writeValueAsString(args);
        } catch (JsonProcessingException e) {
            arguments = args != null ? args.toString() : "{}";
        }
        return ToolCall.builder()
                .name(toolName)
                .arguments(arguments)
                .build();
    }

    private ToolResult convertResult(String toolName,
                                      lyjew.com.lyclaw.tool.ToolResult innerResult,
                                      long startTime) {
        long elapsed = System.currentTimeMillis() - startTime;
        return ToolResult.builder()
                .toolName(toolName)
                .success(innerResult.isSuccess())
                .output(innerResult.isSuccess() ? innerResult.getResult() : null)
                .errorMessage(innerResult.isSuccess() ? null : innerResult.getError())
                .durationMs(elapsed > 0 ? elapsed : innerResult.getElapsedMs())
                .build();
    }
}
