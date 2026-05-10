package lyjew.com.lyclaw.infra.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class MicrometerMetricsCollector implements MetricsCollector {

    private final MeterRegistry meterRegistry;

    private final AtomicLong llmCallCount = new AtomicLong();
    private final AtomicLong toolCallCount = new AtomicLong();
    private final AtomicLong pipelineStageCount = new AtomicLong();
    private final AtomicLong memoryRetrievalCount = new AtomicLong();
    private final AtomicLong agentTaskCount = new AtomicLong();
    private final AtomicLong totalTokens = new AtomicLong();
    private final AtomicLong failedToolCalls = new AtomicLong();
    private final AtomicLong totalPipelineRuns = new AtomicLong();
    private final AtomicLong failedAgentTasks = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();

    private final AtomicLong llmLatencyNs = new AtomicLong();
    private final AtomicLong toolLatencyNs = new AtomicLong();
    private final AtomicLong pipelineDurationNs = new AtomicLong();

    private final ConcurrentHashMap<String, AtomicLong> stageDurations = new ConcurrentHashMap<>();

    private Counter micrometerLlmCalls;
    private Counter micrometerToolCalls;
    private Counter micrometerErrors;
    private Timer micrometerLlmLatency;

    public MicrometerMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        initMicrometerMeters();
    }

    private void initMicrometerMeters() {
        if (meterRegistry != null) {
            this.micrometerLlmCalls = Counter.builder("lyclaw.llm.calls")
                    .description("Total LLM API calls").register(meterRegistry);
            this.micrometerToolCalls = Counter.builder("lyclaw.tool.calls")
                    .description("Total tool invocations").register(meterRegistry);
            this.micrometerErrors = Counter.builder("lyclaw.errors")
                    .description("Total error count").register(meterRegistry);
            this.micrometerLlmLatency = Timer.builder("lyclaw.llm.latency")
                    .description("LLM call latency").register(meterRegistry);
        }
    }

    @Override
    public void recordLlmCall(String provider, String model,
                               int promptTokens, int completionTokens, long latencyMs) {
        llmCallCount.incrementAndGet();
        totalTokens.addAndGet(promptTokens + completionTokens);
        llmLatencyNs.addAndGet(TimeUnit.MILLISECONDS.toNanos(latencyMs));

        if (micrometerLlmCalls != null) micrometerLlmCalls.increment();
        if (micrometerLlmLatency != null) micrometerLlmLatency.record(latencyMs, TimeUnit.MILLISECONDS);

        log.debug("[Metrics] LLM call: provider={}, model={}, prompt={}, completion={}, latency={}ms",
                provider, model, promptTokens, completionTokens, latencyMs);
    }

    @Override
    public void recordToolCall(String toolName, boolean success, long latencyMs) {
        toolCallCount.incrementAndGet();
        toolLatencyNs.addAndGet(TimeUnit.MILLISECONDS.toNanos(latencyMs));

        if (!success) {
            failedToolCalls.incrementAndGet();
            errorCount.incrementAndGet();
            if (micrometerErrors != null) micrometerErrors.increment();
        }

        if (micrometerToolCalls != null) micrometerToolCalls.increment();

        log.debug("[Metrics] Tool call: name={}, success={}, latency={}ms", toolName, success, latencyMs);
    }

    @Override
    public void recordPipelineStage(String stageName, long durationMs) {
        pipelineStageCount.incrementAndGet();
        stageDurations.computeIfAbsent(stageName, k -> new AtomicLong()).addAndGet(durationMs);
        log.debug("[Metrics] Pipeline stage: {} completed in {}ms", stageName, durationMs);
    }

    @Override
    public void recordMemoryRetrieval(long durationMs, int resultCount) {
        memoryRetrievalCount.incrementAndGet();
        log.debug("[Metrics] Memory retrieval: {}ms, {} results", durationMs, resultCount);
    }

    @Override
    public void recordAgentTask(String agentId, boolean success, long durationMs) {
        agentTaskCount.incrementAndGet();
        if (!success) {
            failedAgentTasks.incrementAndGet();
            errorCount.incrementAndGet();
            if (micrometerErrors != null) micrometerErrors.increment();
        }
        log.debug("[Metrics] Agent task: agent={}, success={}, duration={}ms", agentId, success, durationMs);
    }

    public void recordPipelineRun(long durationMs) {
        totalPipelineRuns.incrementAndGet();
        pipelineDurationNs.addAndGet(TimeUnit.MILLISECONDS.toNanos(durationMs));
    }

    public void recordError() {
        errorCount.incrementAndGet();
        if (micrometerErrors != null) micrometerErrors.increment();
    }

    @Override
    public MetricsSnapshot getSnapshot() {
        long llmCalls = llmCallCount.get();
        long toolCalls = toolCallCount.get();
        long pipelineRuns = totalPipelineRuns.get();

        return MetricsSnapshot.builder()
                .totalLlmCalls(llmCalls)
                .totalTokensConsumed(totalTokens.get())
                .avgLlmLatencyMs(llmCalls > 0
                        ? TimeUnit.NANOSECONDS.toMillis(llmLatencyNs.get()) / (double) llmCalls : 0.0)
                .totalToolCalls(toolCalls)
                .failedToolCalls(failedToolCalls.get())
                .avgToolLatencyMs(toolCalls > 0
                        ? TimeUnit.NANOSECONDS.toMillis(toolLatencyNs.get()) / (double) toolCalls : 0.0)
                .totalPipelineRuns(pipelineRuns)
                .avgPipelineDurationMs(pipelineRuns > 0
                        ? TimeUnit.NANOSECONDS.toMillis(pipelineDurationNs.get()) / (double) pipelineRuns : 0.0)
                .totalAgentTasks(agentTaskCount.get())
                .failedAgentTasks(failedAgentTasks.get())
                .stageDurations(toStageDurationMap())
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public long getLlmCallCount() { return llmCallCount.get(); }
    public long getToolCallCount() { return toolCallCount.get(); }
    public long getPipelineStageCount() { return pipelineStageCount.get(); }
    public long getMemoryRetrievalCount() { return memoryRetrievalCount.get(); }
    public long getAgentTaskCount() { return agentTaskCount.get(); }
    public long getTotalTokens() { return totalTokens.get(); }

    public double getAvgLatencyMs() {
        long calls = llmCallCount.get();
        return calls > 0 ? TimeUnit.NANOSECONDS.toMillis(llmLatencyNs.get()) / (double) calls : 0.0;
    }

    public long getErrorCount() { return errorCount.get(); }

    public void reset() {
        llmCallCount.set(0); toolCallCount.set(0); pipelineStageCount.set(0);
        memoryRetrievalCount.set(0); agentTaskCount.set(0); totalTokens.set(0);
        failedToolCalls.set(0); totalPipelineRuns.set(0); failedAgentTasks.set(0); errorCount.set(0);
        llmLatencyNs.set(0); toolLatencyNs.set(0); pipelineDurationNs.set(0);
        stageDurations.clear();
        log.info("[Metrics] All counters reset to zero");
    }

    private Map<String, Long> toStageDurationMap() {
        Map<String, Long> result = new HashMap<>();
        stageDurations.forEach((key, value) -> result.put(key, value.get()));
        return result;
    }
}
