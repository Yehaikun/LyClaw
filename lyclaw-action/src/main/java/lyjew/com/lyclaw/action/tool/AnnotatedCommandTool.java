package lyjew.com.lyclaw.action.tool;

import lyjew.com.lyclaw.framework.annotation.Tool;
import lyjew.com.lyclaw.framework.annotation.Param;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

@Tool(name = "command",
      description = "在沙箱环境中执行系统命令",
      readonly = false,
      group = "builtin")
public class AnnotatedCommandTool {

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_LENGTH = 10000;

    public String execute(
        @Param(name = "command", description = "要执行的shell命令")
        String command
    ) {
        try {
            if (command == null || command.isBlank()) {
                return "命令为空";
            }

            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

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
