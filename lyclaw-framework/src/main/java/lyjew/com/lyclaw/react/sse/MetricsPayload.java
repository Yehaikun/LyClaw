package lyjew.com.lyclaw.react.sse;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class MetricsPayload extends SsePayload {

    private final long totalDurationMs;
    private final int tasksProcessed;
    private final int toolCalls;
    private final int totalTokens;

    public MetricsPayload(
            @JsonProperty("totalDurationMs") long totalDurationMs,
            @JsonProperty("tasksProcessed") int tasksProcessed,
            @JsonProperty("toolCalls") int toolCalls,
            @JsonProperty("totalTokens") int totalTokens) {
        super(SseEventType.METRICS);
        this.totalDurationMs = totalDurationMs;
        this.tasksProcessed = tasksProcessed;
        this.toolCalls = toolCalls;
        this.totalTokens = totalTokens;
    }

    public long getTotalDurationMs() { return totalDurationMs; }
    public int getTasksProcessed() { return tasksProcessed; }
    public int getToolCalls() { return toolCalls; }
    public int getTotalTokens() { return totalTokens; }
}
