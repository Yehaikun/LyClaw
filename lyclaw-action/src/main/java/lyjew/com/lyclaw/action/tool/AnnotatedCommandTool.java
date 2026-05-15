package lyjew.com.lyclaw.action.tool;

import lyjew.com.lyclaw.action.util.CommandExecutor;
import lyjew.com.lyclaw.annotation.tool.Tool;
import lyjew.com.lyclaw.annotation.tool.Param;
import lyjew.com.lyclaw.error.ToolExecuteException;

/**
 * 系统命令执行工具，在子进程中通过 {@code sh -c} 执行 Shell 命令。
 *
 * <p>该工具具有写入能力（readonly = false），因此受沙箱安全级别限制，
 * 仅在 CONTAINER 或 ISOLATED 级别下通过独立进程执行。
 * 命令执行有 30 秒超时，输出限制在 10000 字符以内，超出部分截断。</p>
 *
 * <p>退出码 0 表示成功；非 0 或超时时抛出 {@link ToolExecuteException}，
 * 令上层框架正确标记 success=false，而非将错误文本包装为成功结果。</p>
 */
@Tool(name = "command",
      description = "在本机环境中执行系统命令，主意不要执行危险命令",
      readonly = false,
      group = "builtin")
public class AnnotatedCommandTool {

    /** 命令执行超时时间（秒） */
    private static final int TIMEOUT_SECONDS = 30;
    /** 输出最大长度限制 */
    private static final int MAX_OUTPUT_LENGTH = 10000;

    /**
     * 执行系统命令，委托给 {@link CommandExecutor}。
     *
     * @param command 要执行的 Shell 命令
     * @return 执行结果
     * @throws ToolExecuteException 命令为空、超时或退出码非0时抛出
     */
    public String execute(
        @Param(name = "command", description = "要执行的shell命令")
        String command
    ) {
        if (command == null || command.isBlank()) {
            throw ToolExecuteException.of("command", "命令为空");
        }

        CommandExecutor.CommandResult cr = CommandExecutor.execute(
                command, TIMEOUT_SECONDS, MAX_OUTPUT_LENGTH);

        if (cr.timedOut()) {
            throw ToolExecuteException.of("command",
                    "命令执行超时（" + TIMEOUT_SECONDS + "秒）");
        }
        if (cr.isSuccess()) {
            return cr.output().isEmpty() ? "命令执行成功，无输出" : cr.output();
        }
        throw ToolExecuteException.of("command",
                "退出码 " + cr.exitCode() + ": " + cr.output());
    }
}
