package lyjew.com.lyclaw.error;

import lyjew.com.lyclaw.exception.LyClawException;

/**
 * 工具执行异常，当工具调用过程中发生错误时抛出。
 *
 * <p>携带工具名称和原始异常信息，错误码统一为 TOOL_EXEC_ERROR，HTTP 500。</p>
 */
public class ToolExecuteException extends LyClawException {

    /** 发生错误的工具名称 */
    private final String toolName;

    /**
     * @param toolName 工具名称
     * @param message  错误描述
     * @param cause    原始异常
     */
    public ToolExecuteException(String toolName, String message, Throwable cause) {
        super("TOOL_EXEC_ERROR", 500, message, cause);
        this.toolName = toolName;
    }

    /** 创建无原始异常的工具执行异常。 */
    public static ToolExecuteException of(String toolName, String message) {
        return new ToolExecuteException(toolName, message, null);
    }

    /** 从原始异常创建工具执行异常，自动提取异常消息。 */
    public static ToolExecuteException of(String toolName, Throwable cause) {
        return new ToolExecuteException(toolName,
                "Tool '" + toolName + "' execution failed: " + cause.getMessage(), cause);
    }

    /** @return 发生错误的工具名称 */
    public String getToolName() { return toolName; }
}
