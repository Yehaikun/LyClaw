package lyjew.com.lyclaw.action.tool;

import lyjew.com.lyclaw.action.util.CommandExecutor;
import lyjew.com.lyclaw.annotation.tool.Tool;
import lyjew.com.lyclaw.annotation.tool.Param;
import lyjew.com.lyclaw.error.ToolExecuteException;

/**
 * 系统命令执行工具，在子进程中通过 {@code sh -c} 执行 Shell 命令。
 *
 * <p>该工具具有写入能力（readonly = false），因此受沙箱安全级别限制。
 * 命令执行有 30 秒超时，输出限制在 10000 字符以内，超出部分截断。</p>
 *
 * <p>命令执行完成即返回输出（含退出码），不因非零退出码判定工具失败——
 * 工具只负责执行并透明传递结果，由 LLM 根据输出文本自行理解命令执行情况。</p>
 */
@Tool(name = "command",
      description = "在本机环境中执行系统命令，注意不要执行危险命令，并且你命令不能长度太短，比如cd到某个目录，不能只写这个，不然步骤太多了容易出错误",
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
     * <p>命令执行完成即透明返回输出文本，不因非零退出码判定失败。
     * 由于 {@link CommandExecutor} 已合并 stderr 到 stdout，
     * 错误信息自然包含在输出中，无需额外标注。</p>
     *
     * @param command 要执行的 Shell 命令
     * @return 命令输出原文（合并 stdout/stderr）
     * @throws ToolExecuteException 命令为空或执行超时时抛出
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

        return cr.output().isEmpty() ? "(无输出)" : cr.output();
    }
}
