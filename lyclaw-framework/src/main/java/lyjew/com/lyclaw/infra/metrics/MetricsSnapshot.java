package lyjew.com.lyclaw.infra.metrics;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class MetricsSnapshot {

    private long totalLlmCalls;
    private long totalTokensConsumed;
    private long totalToolCalls;
    private long failedToolCalls;
    private double avgLlmLatencyMs;
    private double avgToolLatencyMs;
    private long totalPipelineRuns;
    private double avgPipelineDurationMs;
    private long totalAgentTasks;
    private long failedAgentTasks;
    private Map<String, Long> stageDurations;
    private long timestamp;
}
