package lyjew.com.lyclaw.action.tool;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class CommandTool implements Tool {

    private static final String TOOL_NAME = "command";
    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_LENGTH = 10000;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public ToolResult execute(ToolCall toolCall, ChatContext context) {
        long startTime = System.currentTimeMillis();
        try {
            String command = extractCommand(toolCall);
            if (command == null || command.isBlank()) {
                return ToolResult.failure("命令为空");
            }
            log.info("执行命令: {}", command);

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return ToolResult.failure("命令执行超时（" + TIMEOUT_SECONDS + "秒）");
            }

            String output = readOutput(process);
            long elapsed = System.currentTimeMillis() - startTime;
            int exitCode = process.exitValue();

            if (exitCode == 0) {
                String result = output.isEmpty() ? "命令执行成功，无输出" : output;
                return new ToolResult(true, result, null, elapsed, 0);
            } else {
                String errorMsg = output.isEmpty() ? "无错误输出" : output;
                return new ToolResult(false, null,
                        "命令执行失败，退出码 " + exitCode + ":\n" + errorMsg, elapsed, 0);
            }
        } catch (Exception e) {
            log.error("命令执行异常", e);
            long elapsed = System.currentTimeMillis() - startTime;
            return new ToolResult(false, null, "命令执行异常: " + e.getMessage(), elapsed, 0);
        }
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .source("builtin")
                .description("在 Linux 服务器上执行 shell 命令并返回输出结果。以当前进程用户身份运行，无 root 权限。超时 30 秒。")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "command", Map.of(
                                        "type", "string",
                                        "description", "要执行的 shell 命令"
                                )
                        ),
                        "required", List.of("command")
                ))
                .build();
    }

    private String readOutput(Process process) throws java.io.IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
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
        return output.toString();
    }

    private String extractCommand(ToolCall toolCall) {
        String args = toolCall.getArguments();
        if (args == null || args.isEmpty()) return "";
        int cmdIdx = args.indexOf("\"command\"");
        if (cmdIdx < 0) return args.replaceAll("[\"{}\\s]", "").trim();
        int start = args.indexOf("\"", args.indexOf(":", cmdIdx + 8) + 1) + 1;
        int end = args.indexOf("\"", start);
        if (end < 0 || end <= start) return "";
        return args.substring(start, end);
    }
}
