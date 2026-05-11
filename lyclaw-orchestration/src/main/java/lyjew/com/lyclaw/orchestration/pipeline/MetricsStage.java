package lyjew.com.lyclaw.orchestration.pipeline;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.event.Event;
import lyjew.com.lyclaw.event.EventBus;
import lyjew.com.lyclaw.infra.metrics.MetricsCollector;
import lyjew.com.lyclaw.infra.metrics.MetricsSnapshot;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;

/**
 * 指标采集阶段（同步管线，order=3）。
 *
 * 在管线末尾采集执行指标：LLM 调用次数、token 用量、管线各阶段耗时。
 * 解析 ChatResult 中的 token 用量字符串（如 "prompt=100 completion=200 total=300"），
 * 记录到 MetricsCollector。同时通过 EventBus 发布 METRICS_COLLECTED 事件。
 */
@Slf4j
public class MetricsStage implements PipelineStage {

    private final EventBus eventBus;
    private final MetricsCollector metricsCollector;

    public MetricsStage(@org.springframework.lang.Nullable EventBus eventBus,
                         @org.springframework.lang.Nullable MetricsCollector metricsCollector) {
        this.eventBus = eventBus;
        this.metricsCollector = metricsCollector;
        log.info("[MetricsStage] Initialized");
    }

    /**
     * 采集并记录执行指标。
     *
     * @param context 聊天上下文
     * @param chain   管线链
     */
    @Override
    public void process(ChatContext context, Chain chain) {
        log.info("[MetricsStage] Starting metrics collection...");

        // 标记追踪结束
        context.getTracing().markEnd();

        ChatResult result = context.getResult();
        if (result != null) {
            // 解析 token 用量并记录 LLM 调用指标
            String tokenUsage = result.getTokenUsage();
            long durationMs = result.getDurationMs();
            log.info("[MetricsStage] Chat complete: tokenUsage={}, durationMs={}ms", tokenUsage, durationMs);

            if (metricsCollector != null) {
                try {
                    // 解析 "prompt=X completion=Y total=Z" 格式的字符串
                    int[] tokens = parseTokenUsage(tokenUsage);
                    metricsCollector.recordLlmCall(
                            context.getModelProvider().getConfiguredAdapter().getProvider(),
                            context.getModelProvider().getConfiguredAdapter().getModel(),
                            tokens[0], tokens[1], durationMs);
                } catch (Exception e) {
                    log.warn("[MetricsStage] Failed to record LLM call metrics: {}", e.getMessage());
                }
            }
        } else {
            long durationMs = context.getTracing().getTotalDuration();
            log.info("[MetricsStage] Chat complete (no ChatResult): durationMs={}ms", durationMs);
        }

        // 记录管线总耗时和快照
        if (metricsCollector != null) {
            try {
                long totalDuration = context.getTracing().getTotalDuration();
                metricsCollector.recordPipelineStage("pipeline_total", totalDuration);

                MetricsSnapshot snapshot = metricsCollector.getSnapshot();
                log.debug("[MetricsStage] Metrics snapshot: llmCalls={}, toolCalls={}, tokens={}",
                        snapshot.getTotalLlmCalls(), snapshot.getTotalToolCalls(),
                        snapshot.getTotalTokensConsumed());
            } catch (Exception e) {
                log.warn("[MetricsStage] Failed to record pipeline metrics: {}", e.getMessage());
            }
        }

        // 发布指标采集完成事件
        if (eventBus != null) {
            eventBus.publish(new Event("MetricsStage", "METRICS_COLLECTED"));
        }
        log.info("[MetricsStage] Completed");
        chain.next(context);
    }

    /**
     * 解析 "prompt=X completion=Y total=Z" 格式的 token 用量字符串。
     *
     * @return int[3] 数组：[prompt, completion, total]
     */
    private int[] parseTokenUsage(String tokenUsage) {
        int prompt = 0, completion = 0, total = 0;
        if (tokenUsage != null) {
            for (String part : tokenUsage.split("\\s+")) {
                if (part.startsWith("prompt=")) {
                    prompt = parseIntSafely(part.substring(7));
                } else if (part.startsWith("completion=")) {
                    completion = parseIntSafely(part.substring(11));
                } else if (part.startsWith("total=")) {
                    total = parseIntSafely(part.substring(6));
                }
            }
        }
        // 如果没解析到 total，用 prompt+completion 推算
        if (total == 0) total = prompt + completion;
        return new int[]{prompt, completion, total};
    }

    /** 安全解析整数，失败返回 0 */
    private int parseIntSafely(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public int getOrder() {
        return 3;
    }

    @Override
    public String getStageName() {
        return "Metrics";
    }
}
