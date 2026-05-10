package lyjew.com.lyclaw.tool.impl;

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

/**
 * Linux 命令执行工具 —— 在服务器上执行 Linux shell 命令并返回输出结果。
 *
 * <p><b>安全限制</b>：
 * <ul>
 *   <li>不会提升权限，以当前进程用户身份运行（无 sudo/root）</li>
 *   <li>继承当前进程的环境变量和工作目录</li>
 *   <li>命令通过 {@code sh -c} 执行，支持管道、重定向等</li>
 *   <li>默认超时 30 秒，防止命令挂死</li>
 *   <li>输出最多截取 10000 字符，防止无限输出</li>
 * </ul>
 * </p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>查看系统状态（磁盘、内存、进程）</li>
 *   <li>读取日志文件</li>
 *   <li>执行简单脚本</li>
 *   <li>文件操作（ls、cat、grep 等不需要 root 的操作）</li>
 * </ul>
 * </p>
 *
 * <p><b>不适用场景</b>：
 * <ul>
 *   <li>需要 root 权限的操作（安装软件、修改系统配置等）</li>
 *   <li>交互式命令（vim、top 等需要 TTY 的程序）</li>
 *   <li>长时间运行的守护进程</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Tool
 */
@Slf4j
@Component
public class CommandTool implements Tool {

    /** 工具名称 */
    private static final String TOOL_NAME = "command";

    /** 命令执行超时（秒） */
    private static final int TIMEOUT_SECONDS = 30;

    /** 输出最大字符数 */
    private static final int MAX_OUTPUT_LENGTH = 10000;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public ToolResult execute(ToolCall toolCall, ChatContext context) {
        try {
            String command = parseCommand(toolCall);
            if (command == null || command.isBlank()) {
                return ToolResult.failure("命令为空");
            }

            log.info("  [CommandTool] 执行命令: {}", command);

            // 通过 sh -c 执行，支持管道和重定向
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);

            // 继承当前进程的环境变量和工作目录
            pb.redirectErrorStream(true);

            long startTime = System.currentTimeMillis();
            Process process = pb.start();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                log.warn("  [CommandTool] 命令超时: {}", command);
                return ToolResult.failure("命令执行超时（" + TIMEOUT_SECONDS + "秒）");
            }

            // 读取输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() + line.length() + 1 > MAX_OUTPUT_LENGTH) {
                        output.append("...（输出已截断，最多 " + MAX_OUTPUT_LENGTH + " 字符）");
                        break;
                    }
                    if (output.length() > 0) output.append("\n");
                    output.append(line);
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            int exitCode = process.exitValue();
            log.info("  [CommandTool] 命令完成: exitCode={}, 耗时={}ms, 输出长度={}",
                    exitCode, elapsed, output.length());

            if (exitCode == 0) {
                String result = output.length() > 0 ? output.toString() : "命令执行成功，无输出";
                return ToolResult.success("命令执行成功（耗时" + elapsed + "ms）:\n" + result);
            } else {
                String errorMsg = output.length() > 0 ? output.toString() : "无错误输出";
                return ToolResult.failure("命令执行失败，退出码 " + exitCode + ":\n" + errorMsg);
            }

        } catch (Exception e) {
            log.error("  [CommandTool] 命令执行异常", e);
            return ToolResult.failure("命令执行异常: " + e.getMessage());
        }
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("在 Linux 服务器上执行 shell 命令并返回输出结果。"
                        + "以当前进程用户身份运行，无 root 权限（不能使用 sudo）。"
                        + "支持管道（|）、重定向（>）、变量替换等 shell 特性。"
                        + "适合查看系统状态、读取文件、执行脚本。"
                        + "不适合交互式程序或需要 root 的操作。超时 30 秒。")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "command", Map.of(
                                        "type", "string",
                                        "description", "要执行的 shell 命令，"
                                                + "如 \"ls -la /tmp\" 或 \"df -h\"。"
                                                + "不使用 sudo。"
                                )
                        ),
                        "required", List.of("command")
                ))
                .build();
    }

    /**
     * 从 toolCall.arguments 中提取 command 参数。
     */
    private String parseCommand(ToolCall toolCall) {
        String args = toolCall.getArguments();
        if (args == null || args.isEmpty()) return "";

        // JSON 格式：{"command":"ls -la"}
        int cmdIdx = args.indexOf("\"command\"");
        if (cmdIdx < 0) return args.replaceAll("[\"{}\\s]", "").trim();

        int colonIdx = args.indexOf(":", cmdIdx + 8);
        if (colonIdx < 0) return "";

        int quoteStart = args.indexOf("\"", colonIdx + 1);
        if (quoteStart < 0) return "";

        int quoteEnd = args.indexOf("\"", quoteStart + 1);
        if (quoteEnd < 0 || quoteEnd <= quoteStart) return "";

        return args.substring(quoteStart + 1, quoteEnd);
    }
}
