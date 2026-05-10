package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;

import java.util.List;

/**
 * Chain 默认实现 —— 控制 PipelineStage 列表的遍历和调用。
 *
 * <p>内部维护 stage 列表和当前索引。每次调用 {@link #proceed(ChatContext)} 时：
 * <ol>
 *   <li>while 循环遍历 Stage</li>
 *   <li>每次取出当前 Stage 并调 process(context, chain)</li>
 *   <li>Stage 内部调 chain.next() 控制是否继续（为空操作，由 while 循环推进）</li>
 *   <li>Stage 内部调 chain.breakChain() 可中断整个流程</li>
 * </ol>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Chain
 * @see PipelineStage
 */
public class DefaultChain implements Chain {

    private final List<PipelineStage> stages;
    private int currentIndex;
    private boolean broken = false;

    public DefaultChain(List<PipelineStage> stages, int startIndex) {
        this.stages = stages;
        this.currentIndex = startIndex;
    }

    /**
     * 从头开始执行所有 Stage。
     * 每个 Stage 通过 next() 或 breakChain() 决定是否继续。
     * 执行前先调用 supports() 判断，返回 false 时跳过此 Stage。
     */
    public void proceed(ChatContext context) {
        while (currentIndex < stages.size() && !broken) {
            PipelineStage stage = stages.get(currentIndex);
            currentIndex++;
            if (!stage.supports(context)) {
                continue; // supports=false 时跳过本 Stage
            }
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
