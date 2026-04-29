package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.context.ChatContext;
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
 * <p><b>设计动机</b>：将指标采集独立为一个阶段。新增指标时只需修改 MetricsStage，
 * 不影响其他阶段。不需要指标时直接从 Pipeline 移除即可。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
@Component
public class MetricsStage implements PipelineStage {

    private final EventBus eventBus;

    public MetricsStage(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public void process(ChatContext context, Chain chain) {
        // 标记追踪结束
        context.getTracing().markEnd();

        // 发布指标采集事件
        eventBus.publish(new Event("MetricsStage", "METRICS_COLLECTED"));

        chain.next(context);
    }

    @Override
    public int getOrder() {
        return 3; // 第四阶段
    }

    @Override
    public String getStageName() {
        return "Metrics";
    }
}