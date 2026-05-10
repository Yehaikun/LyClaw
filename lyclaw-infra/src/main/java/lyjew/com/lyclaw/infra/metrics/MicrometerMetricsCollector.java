package lyjew.com.lyclaw.infra.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 Micrometer 的指标采集器实现。
 *
 * @since 2.0
 */
@Component
public class MicrometerMetricsCollector implements MetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(MicrometerMetricsCollector.class);

    private final MeterRegistry meterRegistry;

    // 累积计数器
    private final AtomicLong totalLlmCalls = new AtomicLong();
    private final AtomicLong totalTokensConsumed = new AtomicLong();
    private final AtomicLong totalToolCalls = new AtomicLong();
    private final AtomicLong failedToolCalls = new AtomicLong();
    private final AtomicLong totalPipelineRuns = new AtomicLong();
    private final AtomicLong totalAgentTasks = new AtomicLong();
    private final AtomicLong failedAgentTasks = new AtomicLong();

    // 延迟累计
    private final AtomicLong totalLlmLatencyNs = new AtomicLong();
    private final AtomicLong totalToolLatencyNs = new AtomicLong();
    private final AtomicLong totalPipelineDurationNs = new AtomicLong();

    // 阶段耗时记录
    private final ConcurrentHashMap<String, AtomicLong> stageDurations = new ConcurrentHashMap<>();

    public MicrometerMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordLlmCall(String provider, String model, int promptTokens, int completionTokens, long latencyMs) {
        totalLlmCalls.incrementAndGet();
        totalTokensConsumed.addAndGet(promptTokens + completionTokens);
        totalLlmLatencyNs.addAndGet(TimeUnit.MILLISECONDS.toNanos(latencyMs));

        log.debug("[Metrics] LLM call: provider={}, model={}, prompt={}, completion={}, latency={}ms",
                provider, model, promptTokens, completionTokens, latencyMs);
    }

    @Override
    public void recordToolCall(String toolName, boolean success, long latencyMs) {
        totalToolCalls.incrementAndGet();
        if (!success) {
            failedToolCalls.incrementAndGet();
        }
        totalToolLatencyNs.addAndGet(TimeUnit.MILLISECONDS.toNanos(latencyMs));

        log.debug("[Metrics] Tool call: name={}, success={}, latency={}ms", toolName, success, latencyMs);
    }

    @Override
    public void recordPipelineStage(String stageName, long durationMs) {
        stageDurations.computeIfAbsent(stageName, k -> new AtomicLong())
                .addAndGet(durationMs);

        log.debug("[Metrics] Pipeline stage: {} completed in {}ms", stageName, durationMs);
    }

    @Override
    public void recordMemoryRetrieval(long durationMs, int resultCount) {
        log.debug("[Metrics] Memory retrieval: {}ms, {} results", durationMs, resultCount);
    }

    @Override
    public void recordAgentTask(String agentId, boolean success, long durationMs) {
        totalAgentTasks.incrementAndGet();
        if (!success) {
            failedAgentTasks.incrementAndGet();
        }

        log.debug("[Metrics] Agent task: agent={}, success={}, duration={}ms", agentId, success, durationMs);
    }

    @Override
    public MetricsSnapshot getSnapshot() {
        long llmCalls = totalLlmCalls.get();
        long toolCalls = totalToolCalls.get();

        return MetricsSnapshot.builder()
                .totalLlmCalls(llmCalls)
                .totalTokensConsumed(totalTokensConsumed.get())
                .totalToolCalls(toolCalls)
                .failedToolCalls(failedToolCalls.get())
                .avgLlmLatencyMs(llmCalls > 0 ? TimeUnit.NANOSECONDS.toMillis(totalLlmLatencyNs.get()) / (double) llmCalls : 0)
                .avgToolLatencyMs(toolCalls > 0 ? TimeUnit.NANOSECONDS.toMillis(totalToolLatencyNs.get()) / (double) toolCalls : 0)
                .totalPipelineRuns(totalPipelineRuns.get())
                .avgPipelineDurationMs(0)
                .totalAgentTasks(totalAgentTasks.get())
                .failedAgentTasks(failedAgentTasks.get())
                .stageDurations(toStageDurationMap())
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private Map<String, Long> toStageDurationMap() {
        Map<String, Long> result = new HashMap<>();
        stageDurations.forEach((key, value) -> result.put(key, value.get()));
        return result;
    }
}
