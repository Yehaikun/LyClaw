package lyjew.com.lyclaw.infra.metrics;

/**
 * 指标采集器 —— 收集 LLM 调用、工具调用、Pipeline 阶段等指标。
 *
 * @since 2.0
 */
public interface MetricsCollector {

    void recordLlmCall(String provider, String model, int promptTokens, int completionTokens, long latencyMs);

    void recordToolCall(String toolName, boolean success, long latencyMs);

    void recordPipelineStage(String stageName, long durationMs);

    void recordMemoryRetrieval(long durationMs, int resultCount);

    void recordAgentTask(String agentId, boolean success, long durationMs);

    MetricsSnapshot getSnapshot();
}
