package lyjew.com.lyclaw.react.sse;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ToolApprovalPayload extends SsePayload {

    private final String toolCallId;
    private final String toolName;
    private final String arguments;
    private final String message;

    @JsonCreator
    public ToolApprovalPayload(
            @JsonProperty("toolCallId") String toolCallId,
            @JsonProperty("toolName") String toolName,
            @JsonProperty("arguments") String arguments,
            @JsonProperty("message") String message) {
        super(SseEventType.TOOL_APPROVAL);
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.arguments = arguments;
        this.message = message;
    }

    public String getToolCallId() { return toolCallId; }
    public String getToolName() { return toolName; }
    public String getArguments() { return arguments; }
    public String getMessage() { return message; }
}
