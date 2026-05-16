package lyjew.com.lyclaw.action.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Shell 命令执行工具类，封装 ProcessBuilder 创建、输出读取、超时控制和截断逻辑。
 *
 * <p>为 {@code ToolSandboxImpl} 和 {@code AnnotatedCommandTool} 提供统一的命令执行能力，
 * 消除两处重复的 ProcessBuilder 样板代码。</p>
 */
public final class CommandExecutor {

    private CommandExecutor() { /* 工具类 */ }

    /**
     * 在子进程中执行 Shell 命令并返回结构化结果。
     *
     * <p>通过 {@code sh -c <command>} 启动子进程，合并 stderr 到 stdout。
     * 超时自动强制终止进程，输出超出限制则截断。</p>
     *
     * @param command         要执行的 Shell 命令
     * @param timeoutSeconds  超时时间（秒）
     * @param maxOutputLength 输出最大字符数
     * @return 命令执行结果
     */
    public static CommandResult execute(String command, int timeoutSeconds, int maxOutputLength) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 并发消费输出流，防止管道缓冲区写满导致进程阻塞死锁
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return readOutput(process, maxOutputLength);
                } catch (Exception e) {
                    return "（读取输出异常: " + e.getMessage() + "）";
                }
            });

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(-1, "", true);
            }

            String output = outputFuture.get(5, TimeUnit.SECONDS);
            return new CommandResult(process.exitValue(), output, false);
        } catch (Exception e) {
            return new CommandResult(-1, "进程执行异常: " + e.getMessage(), false);
        }
    }

    private static String readOutput(Process process, int maxLength) throws Exception {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() + line.length() + 1 > maxLength) {
                    output.append("\n...（输出已截断）");
                    break;
                }
                if (output.length() > 0) output.append("\n");
                output.append(line);
            }
        }
        return output.toString();
    }

    /**
     * 命令执行结构化的结果。
     *
     * @param exitCode 进程退出码，超时时为 -1
     * @param output   标准输出/错误合并文本
     * @param timedOut 是否因超时强制终止
     */
    public record CommandResult(int exitCode, String output, boolean timedOut) {
        /** @return 执行是否成功（退出码为 0 且未超时） */
        public boolean isSuccess() {
            return exitCode == 0 && !timedOut;
        }
    }
}
