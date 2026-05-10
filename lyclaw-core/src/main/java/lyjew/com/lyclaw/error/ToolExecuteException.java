package lyjew.com.lyclaw.error;

import lyjew.com.lyclaw.base.exception.LyClawException;

/**
 * 工具执行异常 —— 包装工具执行过程中的错误，带上工具名便于定位问题。
 *
 * <p>当 Tool.execute() 抛出异常时，ToolCallLoop 捕获后将异常包装为
 * ToolExecuteException，附带工具名称和原始异常 cause，
 * 然后回调 ErrorPolicy.onToolError() 做决策。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ErrorPolicy
 */
public class ToolExecuteException extends LyClawException {

    /** 出错的工具名称 */
    private final String toolName;

    public ToolExecuteException(String toolName, String message, Throwable cause) {
        super("TOOL_EXEC_ERROR", 500, message, cause);
        this.toolName = toolName;
    }

    public static ToolExecuteException of(String toolName, String message) {
        return new ToolExecuteException(toolName, message, null);
    }

    public static ToolExecuteException of(String toolName, Throwable cause) {
        return new ToolExecuteException(toolName,
                "Tool '" + toolName + "' execution failed: " + cause.getMessage(), cause);
    }

    public String getToolName() { return toolName; }
}