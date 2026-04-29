package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.pipeline.Pipeline;
import lyjew.com.lyclaw.pipeline.PipelineStage;

import java.util.List;

/**
 * Pipeline 默认实现 —— PipelineBuilder.build() 返回此实例。
 *
 * <p>内部持有按顺序排列的 PipelineStage 列表，execute() 时通过 DefaultChain
 * 依次调用每个阶段的 process() 方法。</p>
 *
 * <p><b>设计动机</b>：Pipeline 是接口，不能直接实例化。
 * PipelineBuilder.build() 需要一个具体实现来承载阶段列表和执行逻辑。
 * DefaultPipeline 就是这个具体实现，它对 builder 以外的模块透明。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Pipeline
 * @see PipelineStage
 * @see PipelineBuilder
 * @see DefaultChain
 */
public class DefaultPipeline implements Pipeline {

    /** 按执行顺序排列的阶段列表 */
    private final List<PipelineStage> stages;

    /**
     * 包级私有构造器 —— 仅由 PipelineBuilder.build() 调用。
     *
     * @param stages 按执行顺序排列的阶段列表
     */
    DefaultPipeline(List<PipelineStage> stages) {
        this.stages = List.copyOf(stages);
    }

    @Override
    public void execute(ChatContext context) {
        new DefaultChain(stages, 0).proceed(context);
    }

    @Override
    public List<PipelineStage> getStages() {
        return stages;
    }
}