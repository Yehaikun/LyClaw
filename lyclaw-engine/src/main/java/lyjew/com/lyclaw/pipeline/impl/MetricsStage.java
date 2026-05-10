package lyjew.com.lyclaw.pipeline.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.event.Event;
import lyjew.com.lyclaw.event.EventBus;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;

import org.springframework.stereotype.Component;

/**
 * Pipeline 第四阶段 —— 指标采集阶段。
 *
 * <p>从 TraceContext 采集各阶段耗时和总耗时，
 * 发布指标采集事件供监控模块消费。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
@Slf4j
@Component
public class MetricsStage implements PipelineStage {

    private final EventBus eventBus;

    public MetricsStage(EventBus eventBus) {
        this.eventBus = eventBus;
        log.info("  [MetricsStage] 构造器");
    }

    @Override
    public void process(ChatContext context, Chain chain) {
        log.info("  [MetricsStage] 开始：采集指标...");

        context.getTracing().markEnd();

        ChatResult result = context.getResult();
        if (result != null) {
            String tokenUsage = result.getTokenUsage();
            long durationMs = result.getDurationMs();
            log.info("  [MetricsStage] 对话完成: tokenUsage={}, durationMs={}ms", tokenUsage, durationMs);
        } else {
            long durationMs = context.getTracing().getTotalDuration();
            log.info("  [MetricsStage] 对话完成(无ChatResult): durationMs={}ms", durationMs);
        }

        eventBus.publish(new Event("MetricsStage", "METRICS_COLLECTED"));
        log.info("  [MetricsStage] 完成");
        chain.next(context);
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
