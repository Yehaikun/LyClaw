package lyjew.com.lyclaw.action.tool;

import lyjew.com.lyclaw.framework.annotation.Tool;
import lyjew.com.lyclaw.framework.annotation.Param;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

/**
 * 系统命令执行工具，在子进程中通过 {@code sh -c} 执行 Shell 命令。
 *
 * <p>该工具具有写入能力（readonly = false），因此受沙箱安全级别限制，
 * 仅在 CONTAINER 或 ISOLATED 级别下通过独立进程执行。
 * 命令执行有 30 秒超时，输出限制在 10000 字符以内，超出部分截断。</p>
 *
 * <p>退出码 0 表示成功，非 0 时返回退出码和错误输出。</p>
 */
@Tool(name = "command",
      description = "在沙箱环境中执行系统命令",
      readonly = false,
      group = "builtin")
public class AnnotatedCommandTool {

    /** 命令执行超时时间（秒） */
    private static final int TIMEOUT_SECONDS = 30;
    /** 输出最大长度限制 */
    private static final int MAX_OUTPUT_LENGTH = 10000;

    /**
     * 执行系统命令。
     *
     * @param command 要执行的 Shell 命令
     * @return 执行结果或错误描述
     */
    public String execute(
        @Param(name = "command", description = "要执行的shell命令")
        String command
    ) {
        try {
            if (command == null || command.isBlank()) {
                return "命令为空";
            }

            // 创建子进程执行 sh -c <command>
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);  // 合并 stderr 到 stdout
            Process process = pb.start();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // 超时强制终止
            if (!finished) {
                process.destroyForcibly();
                return "命令执行超时（" + TIMEOUT_SECONDS + "秒）";
            }

            String output = readOutput(process);
            int exitCode = process.exitValue();

            if (exitCode == 0) {
                return output.isEmpty() ? "命令执行成功，无输出" : output;
            } else {
                String errorMsg = output.isEmpty() ? "无错误输出" : output;
                return "命令执行失败，退出码 " + exitCode + ":\n" + errorMsg;
            }
        } catch (Exception e) {
            return "命令执行异常: " + e.getMessage();
        }
    }

    /**
     * 读取进程的输出流，限制最大长度。
     *
     * @param process 已执行完成的进程对象
     * @return 输出文本
     */
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
}
