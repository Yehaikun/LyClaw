package lyjew.com.lyclaw.action.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lyjew.com.lyclaw.action.tool.ToolSandbox;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工具沙箱实现，在三种执行模式（DIRECT / SANDBOX / PROCESS）下执行工具调用。
 *
 * <p>执行模式说明：
 * <ul>
 *   <li><b>DIRECT</b> - 当前线程直接执行（calculator、current_time、web_search 等只读工具）</li>
 *   <li><b>SANDBOX</b> - 守护线程 + 临时工作目录隔离（可能写文件的工具），执行后自动清理</li>
 *   <li><b>PROCESS</b> - 独立 OS 进程执行，适配 command/script 类工具</li>
 * </ul>
 * </p>
 *
 * <p>沙箱提供健康检查（{@link #isHealthy()}）和优雅销毁（{@link #destroy()}）能力。</p>
 */
@Slf4j
@Component
public class ToolSandboxImpl implements ToolSandbox {

    /** 工具默认执行超时时间（秒） */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    /** 命令执行输出最大长度限制，超出部分截断 */
    private static final int MAX_OUTPUT_LENGTH = 10000;

    /** 沙箱异步线程池，2 个守护线程用于受限/容器/隔离级别的工具执行 */
    private final ExecutorService sandboxExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "sandbox-worker");
        t.setDaemon(true);
        return t;
    });

    /** 沙箱健康状态标记 */
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    /** JSON 序列化工具，用于将参数 map 序列化为 JSON 字符串 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 在指定沙箱安全级别下执行工具。
     *
     * <p>首先检查沙箱健康状态，然后根据安全级别分发执行。</p>
     *
     * @param tool  待执行的工具
     * @param args  工具参数
     * @param level 沙箱安全级别
     * @return 执行结果
     */
    @Override
    public ToolExecutionResult execute(Tool tool, Map<String, Object> args, SandboxLevel level) {
        if (!healthy.get()) {
            return ToolExecutionResult.builder()
                    .toolName(tool.getName())
                    .success(false)
                    .error("沙箱不可用")
                    .elapsedMs(0)
                    .build();
        }

        long startTime = System.currentTimeMillis();
        try {
            // 将参数 Map 构建为 ToolCall 对象
            ToolCall toolCall = buildToolCall(tool.getName(), args);
            SandboxLevel effectiveLevel = level != null ? level : SandboxLevel.DIRECT;
            log.info("沙箱执行: tool={}, sandboxLevel={}, params={}",
                    tool.getName(), effectiveLevel,
                    args != null ? args.keySet() : "[]");
            // 根据执行模式分发到不同的执行方法
            return switch (effectiveLevel) {
                case DIRECT -> executeDirect(tool, toolCall, startTime);
                case SANDBOX -> executeSandbox(tool, toolCall, startTime);
                case PROCESS -> executeProcess(tool, toolCall, args, startTime);
            };
        } catch (Exception e) {
            log.error("沙箱执行异常: tool={}, level={}", tool.getName(), level, e);
            long elapsed = System.currentTimeMillis() - startTime;
            return ToolExecutionResult.builder()
                    .toolName(tool.getName())
                    .success(false)
                    .error("沙箱执行异常: " + e.getMessage())
                    .elapsedMs(elapsed)
                    .build();
        }
    }

    /**
     * 返回沙箱当前是否处于健康状态。
     *
     * @return true 表示沙箱可用
     */
    @Override
    public boolean isHealthy() {
        return healthy.get();
    }

    /**
     * 销毁沙箱，优雅关闭线程池。
     *
     * <p>先标记为不健康状态，再等待线程池中的任务结束（最多 5 秒），
     * 超时则强制关闭。</p>
     */
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

    /**
     * DIRECT 模式：当前线程直接执行，无隔离。
     */
    private ToolExecutionResult executeDirect(Tool tool, ToolCall toolCall, long startTime) {
        ToolExecutionResult innerResult = tool.execute(toolCall, null);
        return convertResult(tool.getName(), innerResult, startTime);
    }

    /**
     * SANDBOX 模式：守护线程 + 临时工作目录隔离执行。
     *
     * <p>隔离机制：
     * <ol>
     *   <li>创建临时目录（lyclaw-sandbox- 前缀）</li>
     *   <li>将 user.dir 系统属性临时切换到临时目录</li>
     *   <li>执行工具</li>
     *   <li>恢复 user.dir</li>
     *   <li>递归删除临时目录及其中的所有文件</li>
     * </ol>
     * </p>
     * <p>工具在独立的守护线程中执行，超时时间 {@value #DEFAULT_TIMEOUT_SECONDS} 秒。</p>
     */
    private ToolExecutionResult executeSandbox(Tool tool, ToolCall toolCall, long startTime) {
        try {
            Future<ToolExecutionResult> future = sandboxExecutor.submit(() -> {
                // 创建临时工作目录
                Path tempDir = Files.createTempDirectory("lyclaw-sandbox-");
                try {
                    String originalDir = System.getProperty("user.dir");
                    // 切换到临时目录
                    System.setProperty("user.dir", tempDir.toString());
                    try {
                        return tool.execute(toolCall, null);
                    } finally {
                        // 恢复原始工作目录
                        System.setProperty("user.dir", originalDir);
                    }
                } finally {
                    // 清理临时目录（递归删除所有文件和子目录）
                    try {
                        Files.walk(tempDir)
                                .sorted(java.util.Comparator.reverseOrder())
                                .forEach(p -> {
                                    try { Files.delete(p); } catch (IOException ignored) { }
                                });
                    } catch (IOException ignored) { }
                }
            });

            ToolExecutionResult innerResult =
                    future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return convertResult(tool.getName(), innerResult, startTime);
        } catch (TimeoutException e) {
            log.error("SANDBOX模式工具执行超时({}秒): tool={}", DEFAULT_TIMEOUT_SECONDS, tool.getName(), e);
            return ToolExecutionResult.builder()
                    .toolName(tool.getName())
                    .success(false)
                    .error("工具执行超时（" + DEFAULT_TIMEOUT_SECONDS + "秒）")
                    .elapsedMs(System.currentTimeMillis() - startTime)
                    .build();
        } catch (Exception e) {
            log.error("SANDBOX 模式执行异常: tool={}", tool.getName(), e);
            return ToolExecutionResult.builder()
                    .toolName(tool.getName())
                    .success(false)
                    .error("受限沙箱执行异常: " + e.getMessage())
                    .elapsedMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    /**
     * PROCESS 模式：command 工具通过独立 OS 进程执行，其他工具降级到 SANDBOX。
     *
     * <p>对 command 工具使用 {@link #executeCommandInProcess} 创建独立 Shell 进程，
     * 并通过 CommandExecutor 提供超时保护和输出截断。</p>
     */
    private ToolExecutionResult executeProcess(Tool tool, ToolCall toolCall,
                                        Map<String, Object> args, long startTime) {
        try {
            if ("command".equals(tool.getName()) && args.containsKey("command")) {
                return executeCommandInProcess(tool.getName(),
                        (String) args.get("command"), startTime);
            }
            // 其他工具：降级到 SANDBOX
            log.debug("工具 {} 不需要进程隔离，降级到 SANDBOX", tool.getName());
            return executeSandbox(tool, toolCall, startTime);
        } catch (Exception e) {
            log.error("PROCESS 模式执行异常: tool={}", tool.getName(), e);
            return ToolExecutionResult.builder()
                    .toolName(tool.getName())
                    .success(false)
                    .error("进程执行异常: " + e.getMessage())
                    .elapsedMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    /**
     * 在独立 OS 进程中执行 Shell 命令，委托给 {@link lyjew.com.lyclaw.action.util.CommandExecutor}。
     *
     * @param toolName  工具名称（用于结果标记）
     * @param command   要执行的 Shell 命令
     * @param startTime 开始时间戳（毫秒）
     * @return 执行结果
     */
    private ToolExecutionResult executeCommandInProcess(String toolName, String command, long startTime) {
        lyjew.com.lyclaw.action.util.CommandExecutor.CommandResult cr =
                lyjew.com.lyclaw.action.util.CommandExecutor.execute(
                        command, DEFAULT_TIMEOUT_SECONDS, MAX_OUTPUT_LENGTH);
        long elapsed = System.currentTimeMillis() - startTime;

        if (cr.timedOut()) {
            return ToolExecutionResult.builder()
                    .toolName(toolName)
                    .success(false)
                    .error("命令执行超时（" + DEFAULT_TIMEOUT_SECONDS + "秒）")
                    .elapsedMs(elapsed)
                    .build();
        }
        // 透明返回命令输出，不根据退出码判断成败——AI 像人一样阅读结果
        String out = cr.output().isEmpty() ? "(无输出)" : cr.output();
        return ToolExecutionResult.builder()
                .toolName(toolName)
                .success(true)
                .result(out)
                .elapsedMs(elapsed)
                .build();
    }

    /**
     * 根据工具名称和参数构建 ToolCall 对象。
     *
     * <p>将参数 Map 序列化为 JSON 字符串作为 arguments。序列化失败时退化为 toString()。</p>
     *
     * @param toolName 工具名称
     * @param args     参数键值对
     * @return ToolCall 对象
     */
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

    /**
     * 将内部 ToolExecutionResult 转换为公开 API 的 ToolExecutionResult。
     *
     * @param toolName    工具名称
     * @param innerResult 内部执行结果
     * @param startTime   执行开始时间戳（毫秒）
     * @return 格式化后的 ToolExecutionResult
     */
    private ToolExecutionResult convertResult(String toolName,
                                      ToolExecutionResult innerResult,
                                      long startTime) {
        long elapsed = System.currentTimeMillis() - startTime;
        return ToolExecutionResult.builder()
                .toolName(toolName)
                .success(innerResult.isSuccess())
                .result(innerResult.isSuccess() ? innerResult.getResult() : null)
                .error(innerResult.isSuccess() ? null : innerResult.getError())
                .elapsedMs(elapsed > 0 ? elapsed : innerResult.getElapsedMs())
                .build();
    }
}
