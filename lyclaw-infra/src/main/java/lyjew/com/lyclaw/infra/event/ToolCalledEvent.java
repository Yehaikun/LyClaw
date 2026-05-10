package lyjew.com.lyclaw.infra.event;

import lyjew.com.lyclaw.event.Event;
import java.util.Map;

public class ToolCalledEvent extends Event {

    private final String toolName;
    private final Map<String, Object> args;
    private final boolean success;
    private final long latencyMs;
    private final String sessionId;

    public ToolCalledEvent(String source, String toolName, Map<String, Object> args,
                           boolean success, long latencyMs, String sessionId) {
        super(source, "TOOL_CALLED");
        this.toolName = toolName;
        this.args = args;
        this.success = success;
        this.latencyMs = latencyMs;
        this.sessionId = sessionId;
    }

    public String getToolName() { return toolName; }
    public Map<String, Object> getArgs() { return args; }
    public boolean isSuccess() { return success; }
    public long getLatencyMs() { return latencyMs; }
    public String getSessionId() { return sessionId; }
}
