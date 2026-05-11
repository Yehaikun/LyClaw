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

/**
 * 基于 Micrometer 的指标收集器，统计 LLM 调用、工具调用、流水线执行等各类运行时指标。
 *
 * <p>同时维护两套计数器：
 * <ul>
 *   <li>内部 {@link AtomicLong} 计数器：用于快照导出和精确查询</li>
 *   <li>Micrometer {@link Counter}/{@link Timer}：用于对接外部监控系统（如 Prometheus）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class MicrometerMetricsCollector implements MetricsCollector {

    private final MeterRegistry meterRegistry;

    /** LLM API 调用次数 */
    private final AtomicLong llmCallCount = new AtomicLong();
    /** 工具调用次数 */
    private final AtomicLong toolCallCount = new AtomicLong();
    /** 流水线阶段执行次数 */
    private final AtomicLong pipelineStageCount = new AtomicLong();
    /** 记忆检索次数 */
    private final AtomicLong memoryRetrievalCount = new AtomicLong();
    /** Agent 任务执行次数 */
    private final AtomicLong agentTaskCount = new AtomicLong();
    /** 总 Token 消耗量 */
    private final AtomicLong totalTokens = new AtomicLong();
    /** 失败的工具调用次数 */
    private final AtomicLong failedToolCalls = new AtomicLong();
    /** 总流水线运行次数 */
    private final AtomicLong totalPipelineRuns = new AtomicLong();
    /** 失败的 Agent 任务数 */
    private final AtomicLong failedAgentTasks = new AtomicLong();
    /** 总错误数 */
    private final AtomicLong errorCount = new AtomicLong();

    /** LLM 调用总延迟（纳秒） */
    private final AtomicLong llmLatencyNs = new AtomicLong();
    /** 工具调用总延迟（纳秒） */
    private final AtomicLong toolLatencyNs = new AtomicLong();
    /** 流水线总耗时（纳秒） */
    private final AtomicLong pipelineDurationNs = new AtomicLong();

    /** 各流水线阶段耗时统计 */
    private final ConcurrentHashMap<String, AtomicLong> stageDurations = new ConcurrentHashMap<>();

    /** Micrometer LLM 调用计数器 */
    private Counter micrometerLlmCalls;
    /** Micrometer 工具调用计数器 */
    private Counter micrometerToolCalls;
    /** Micrometer 错误计数器 */
    private Counter micrometerErrors;
    /** Micrometer LLM 延迟计时器 */
    private Timer micrometerLlmLatency;

    public MicrometerMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        initMicrometerMeters();
    }

    /** 初始化 Micrometer 计量器（Counter/Timer），仅在 MeterRegistry 可用时注册 */
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

    /**
     * 记录一次 LLM 调用。
     *
     * @param provider         模型提供商
     * @param model            模型名称
     * @param promptTokens     提示 Token 数
     * @param completionTokens 补全 Token 数
     * @param latencyMs        调用延迟（毫秒）
     */
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

    /**
     * 记录一次工具调用。失败时同时递增错误计数器。
     *
     * @param toolName  工具名称
     * @param success   是否成功
     * @param latencyMs 执行延迟（毫秒）
     */
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

    /**
     * 记录流水线阶段耗时。
     *
     * @param stageName  阶段名称
     * @param durationMs 耗时（毫秒）
     */
    @Override
    public void recordPipelineStage(String stageName, long durationMs) {
        pipelineStageCount.incrementAndGet();
        stageDurations.computeIfAbsent(stageName, k -> new AtomicLong()).addAndGet(durationMs);
        log.debug("[Metrics] Pipeline stage: {} completed in {}ms", stageName, durationMs);
    }

    /**
     * 记录记忆检索操作。
     *
     * @param durationMs 耗时（毫秒）
     * @param resultCount 返回结果数量
     */
    @Override
    public void recordMemoryRetrieval(long durationMs, int resultCount) {
        memoryRetrievalCount.incrementAndGet();
        log.debug("[Metrics] Memory retrieval: {}ms, {} results", durationMs, resultCount);
    }

    /**
     * 记录 Agent 任务执行。失败时同时递增错误计数器。
     */
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

    /** 记录一次完整的流水线运行 */
    public void recordPipelineRun(long durationMs) {
        totalPipelineRuns.incrementAndGet();
        pipelineDurationNs.addAndGet(TimeUnit.MILLISECONDS.toNanos(durationMs));
    }

    /** 记录一次错误 */
    public void recordError() {
        errorCount.incrementAndGet();
        if (micrometerErrors != null) micrometerErrors.increment();
    }

    /**
     * 生成当前指标快照，包含所有计数器的当前值及计算出的平均值。
     *
     * @return 不可变的指标快照
     */
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

    /** @return 平均 LLM 延迟（毫秒） */
    public double getAvgLatencyMs() {
        long calls = llmCallCount.get();
        return calls > 0 ? TimeUnit.NANOSECONDS.toMillis(llmLatencyNs.get()) / (double) calls : 0.0;
    }

    public long getErrorCount() { return errorCount.get(); }

    /** 重置所有计数器归零 */
    public void reset() {
        llmCallCount.set(0); toolCallCount.set(0); pipelineStageCount.set(0);
        memoryRetrievalCount.set(0); agentTaskCount.set(0); totalTokens.set(0);
        failedToolCalls.set(0); totalPipelineRuns.set(0); failedAgentTasks.set(0); errorCount.set(0);
        llmLatencyNs.set(0); toolLatencyNs.set(0); pipelineDurationNs.set(0);
        stageDurations.clear();
        log.info("[Metrics] All counters reset to zero");
    }

    /** 将阶段耗时 Map 转换为普通 Map 供快照使用 */
    private Map<String, Long> toStageDurationMap() {
        Map<String, Long> result = new HashMap<>();
        stageDurations.forEach((key, value) -> result.put(key, value.get()));
        return result;
    }
}
