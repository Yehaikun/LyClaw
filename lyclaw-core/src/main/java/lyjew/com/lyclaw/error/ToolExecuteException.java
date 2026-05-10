package lyjew.com.lyclaw.error;

import lyjew.com.lyclaw.base.exception.LyClawException;

public class ToolExecuteException extends LyClawException {

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
