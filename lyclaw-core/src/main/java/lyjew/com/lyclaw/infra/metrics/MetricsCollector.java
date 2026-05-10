package lyjew.com.lyclaw.infra.metrics;

public interface MetricsCollector {

    void recordLlmCall(String provider, String model, int promptTokens, int completionTokens, long latencyMs);

    void recordToolCall(String toolName, boolean success, long latencyMs);

    void recordPipelineStage(String stageName, long durationMs);

    void recordMemoryRetrieval(long durationMs, int resultCount);

    void recordAgentTask(String agentId, boolean success, long durationMs);

    MetricsSnapshot getSnapshot();
}
