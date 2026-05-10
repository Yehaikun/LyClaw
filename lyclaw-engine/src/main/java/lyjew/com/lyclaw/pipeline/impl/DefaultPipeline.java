package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.pipeline.Pipeline;
import lyjew.com.lyclaw.pipeline.PipelineStage;

import java.util.List;

/**
 * Pipeline 默认实现 —— PipelineBuilder 默认返回此实例。
 *
 * <p>内部持有按 {@code getOrder()} 排序的 {@link PipelineStage} 列表，
 * execute() 时通过 {@link DefaultChain} 按序遍历每个 Stage 的 process()。</p>
 *
 * <p>当前所有 Stage 都不调用 breakChain，因此 DefaultChain 表现为线性执行。
 * 但通过 Chain 接口保留了每个 Stage "随时中断流程"的能力。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Pipeline
 * @see PipelineStage
 * @see DefaultChain
 */
public class DefaultPipeline implements Pipeline {

    /** 按执行顺序排列的阶段列表（不可变） */
    private final List<PipelineStage> stages;

    /**
     * 包级私有构造器 —— 由 PipelineBuilder 调用。
     *
     * @param stages 已排好序的阶段列表
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
