package lyjew.com.lyclaw.react.sse;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ToolCallPayload extends SsePayload {

    private final String toolCallId;
    private final String name;
    private final String status;
    private final String message;
    private final String arguments;
    private final String result;
    private final Boolean success;

    @JsonCreator
    public ToolCallPayload(
            @JsonProperty("toolCallId") String toolCallId,
            @JsonProperty("name") String name,
            @JsonProperty("status") String status,
            @JsonProperty("message") String message,
            @JsonProperty("arguments") String arguments,
            @JsonProperty("result") String result,
            @JsonProperty("success") Boolean success) {
        super(SseEventType.TOOL_CALL);
        this.toolCallId = toolCallId;
        this.name = name;
        this.status = status;
        this.message = message;
        this.arguments = arguments;
        this.result = result;
        this.success = success;
    }

    public static ToolCallPayload executing(String id, String name, String message, String args) {
        return new ToolCallPayload(id, name, "executing", message, args, null, null);
    }

    public static ToolCallPayload done(String id, String name, String message, String args, String result, boolean success) {
        return new ToolCallPayload(id, name, "done", message, args, result, success);
    }

    public String getToolCallId() { return toolCallId; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public String getMessage() { return message; }
    public String getArguments() { return arguments; }
    public String getResult() { return result; }
    public Boolean getSuccess() { return success; }
}
