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

    @Override
    public void process(ChatContext context, Chain chain) {
        log.info("[MetricsStage] Starting metrics collection...");

        context.getTracing().markEnd();

        ChatResult result = context.getResult();
        if (result != null) {
            String tokenUsage = result.getTokenUsage();
            long durationMs = result.getDurationMs();
            log.info("[MetricsStage] Chat complete: tokenUsage={}, durationMs={}ms", tokenUsage, durationMs);

            if (metricsCollector != null) {
                try {
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

        if (eventBus != null) {
            eventBus.publish(new Event("MetricsStage", "METRICS_COLLECTED"));
        }
        log.info("[MetricsStage] Completed");
        chain.next(context);
    }

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
        if (total == 0) total = prompt + completion;
        return new int[]{prompt, completion, total};
    }

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
