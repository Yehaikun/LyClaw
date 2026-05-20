package lyjew.com.lyclaw.react;

/**
 * Utility for building SSE event strings in the standard format.
 *
 * <p>Each method produces a complete SSE event block (event + data + blank line).
 * These strings can be used as {@code ServerSentEvent.data()} values or written
 * directly to raw SSE sinks.
 *
 * <p>Standard SSE format:
 * <pre>
 *   event: &lt;event_type&gt;
 *   data: &lt;payload&gt;
 *   (blank line)
 * </pre>
 */
public final class SseEventBuilder {

    private SseEventBuilder() {
        // utility class
    }

    /**
     * Build a thinking SSE event containing the model's reasoning/thinking content.
     * Frontend should display this in a collapsible "thinking..." indicator.
     *
     * @param reasoningContent the reasoning content from the model
     * @return complete SSE event string in the format "event: thinking\ndata: &lt;content&gt;\n\n"
     */
    public static String thinkingEvent(String reasoningContent) {
        String content = reasoningContent != null ? reasoningContent : "";
        return "event: thinking\ndata: " + content + "\n\n";
    }

    /**
     * Build a generic SSE event with the given event type and data payload.
     *
     * @param eventType the SSE event type (e.g. "message", "thinking", "tool_call")
     * @param data      the data payload (will be JSON-escaped if needed by caller)
     * @return complete SSE event string
     */
    public static String event(String eventType, String data) {
        String e = eventType != null ? eventType : "message";
        String d = data != null ? data : "";
        return "event: " + e + "\ndata: " + d + "\n\n";
    }

    /**
     * Build an SSE event with JSON-escaped data for safety.
     *
     * @param eventType the SSE event type
     * @param data      the data payload (raw, will be JSON-escaped)
     * @return complete SSE event string
     */
    public static String jsonEvent(String eventType, String data) {
        String e = eventType != null ? eventType : "message";
        String d = data != null ? escapeJson(data) : "";
        return "event: " + e + "\ndata: " + d + "\n\n";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
