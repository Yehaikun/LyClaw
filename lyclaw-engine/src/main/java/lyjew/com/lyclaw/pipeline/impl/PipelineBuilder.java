package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.pipeline.Pipeline;
import lyjew.com.lyclaw.pipeline.PipelineStage;

import java.util.ArrayList;
import java.util.List;

/**
 * Pipeline 构建器 —— 通过链式调用添加/移除阶段，最终 build() 生成 Pipeline 实例。
 *
 * <p>使用建造者模式：</p>
 * <pre>{@code
 * Pipeline pipeline = new PipelineBuilder()
 *     .addStage(new ContextBuildStage(...))
 *     .addStage(new InterceptorStage(...))
 *     .addStage(new ToolCallLoopStage(...))
 *     .addStage(new MetricsStage(...))
 *     .addStage(new ResponseBuildStage(...))
 *     .build();
 * }</pre>
 *
 * <p><b>设计动机</b>：如果直接在 DefaultEngine 中硬编码阶段列表，
 * 要新增阶段就需要改 execute() 方法。通过 PipelineBuilder，DefaultEngine
 * 可以从配置或条件动态编排阶段，新增阶段只需 new + addStage() 两行代码。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Pipeline
 * @see PipelineStage
 */
public class PipelineBuilder {

    /** 阶段列表，保持添加顺序 */
    private final List<PipelineStage> stages = new ArrayList<>();

    /**
     * 在末尾添加一个阶段。
     *
     * @param stage 要添加的阶段
     * @return 当前 Builder（链式调用）
     */
    public PipelineBuilder addStage(PipelineStage stage) {
        stages.add(stage);
        return this;
    }

    /**
     * 按名称移除一个阶段。
     *
     * @param stageName 阶段名称（与 getStageName() 返回值匹配）
     * @return 当前 Builder（链式调用）
     */
    public PipelineBuilder removeStage(String stageName) {
        stages.removeIf(s -> s.getStageName().equals(stageName));
        return this;
    }

    /**
     * 构建 Pipeline 实例。
     *
     * @return 组装好的 Pipeline
     */
    public Pipeline build() {
        return new DefaultPipeline(stages);
    }
}