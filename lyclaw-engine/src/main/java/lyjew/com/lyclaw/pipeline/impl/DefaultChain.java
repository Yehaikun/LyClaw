package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;

import java.util.List;

/**
 * Chain 默认实现 —— 控制 PipelineStage 列表的遍历和调用。
 *
 * <p>内部维护 stage 列表和当前索引。每次调用 proceed(context) 时：
 * <ol>
 *   <li>检查是否还有未执行的 Stage</li>
 *   <li>如果有，获取当前 Stage 并递增索引</li>
 *   <li>调用 Stage.process(context, this) 执行</li>
 *   <li>Stage 内部调用 Chain.next() 或 Chain.breakChain() 控制后续流程</li>
 * </ol>
 * </p>
 *
 * <p><b>设计动机</b>：DefaultPipeline.execute() 需要一种机制来遍历 Stage 列表
 * 并按顺序调用。如果没有 DefaultChain，DefaultPipeline 就需要自己维护索引
 * 和中断标记，职责耦合。DefaultChain 将这些逻辑封装起来，让 Pipeline 只关注编排。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Chain
 * @see PipelineStage
 * @see DefaultPipeline
 */
public class DefaultChain implements Chain {

    /** 按执行顺序排列的阶段列表 */
    private final List<PipelineStage> stages;

    /** 当前执行到的索引（从 0 开始） */
    private int currentIndex;

    /** 是否已被中断 */
    private boolean broken = false;

    /**
     * 构造 DefaultChain 实例。
     *
     * @param stages 按执行顺序排列的阶段列表
     * @param startIndex 起始索引，通常为 0
     */
    public DefaultChain(List<PipelineStage> stages, int startIndex) {
        this.stages = stages;
        this.currentIndex = startIndex;
    }

    /**
     * 开始执行 —— 等同于从当前索引开始逐个调用 Stage.process()。
     *
     * <p>与 next() 不同，proceed() 是入口方法：从头开始执行所有 Stage。
     * 每个 Stage 内部调用 next() 或 breakChain() 决定是否继续。</p>
     *
     * @param context 对话上下文
     */
    public void proceed(ChatContext context) {
        while (currentIndex < stages.size() && !broken) {
            PipelineStage stage = stages.get(currentIndex);
            currentIndex++;
            stage.process(context, this);
        }
    }

    @Override
    public void next(ChatContext context) {
        if (broken) {
            throw new IllegalStateException("Chain has been broken");
        }
        // 由 proceed() 的 while 循环控制推进
    }

    @Override
    public void breakChain(ChatContext context) {
        this.broken = true;
    }

    @Override
    public int getCurrentStage() {
        return currentIndex - 1;
    }
}