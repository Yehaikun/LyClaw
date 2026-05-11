package lyjew.com.lyclaw.orchestration.pipeline;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;

import java.util.List;

/**
 * 默认管线链实现（同步管线）。
 *
 * 按顺序遍历所有 PipelineStage，调用 supports() 检查兼容性后执行 process()。
 * 支持链断裂(breakChain)：当某个阶段判定请求应被拒绝（安全检查失败等），
 * 设置 broken 标记后，后续阶段将跳过执行。
 */
@Slf4j
public class DefaultChain implements Chain {

    /** 所有管线阶段列表 */
    private final List<PipelineStage> stages;
    /** 当前阶段索引 */
    private int currentIndex;
    /** 链是否已断裂 */
    private boolean broken = false;
    /** 断裂原因 */
    private String breakReason;

    public DefaultChain(List<PipelineStage> stages, int startIndex) {
        this.stages = stages;
        this.currentIndex = startIndex;
    }

    /**
     * 按顺序驱动管线执行。
     * 每个阶段执行完会回调 chain.next()，然后继续下一个阶段。
     * 如果某个阶段调用了 breakChain()，循环终止。
     *
     * @param context 聊天上下文
     */
    public void proceed(ChatContext context) {
        while (currentIndex < stages.size() && !broken) {
            PipelineStage stage = stages.get(currentIndex);
            currentIndex++;
            // 跳过不兼容的阶段
            if (!stage.supports(context)) {
                log.debug("[DefaultChain] Skipping stage {} (supports=false)", stage.getStageName());
                continue;
            }
            log.debug("[DefaultChain] Processing stage {} (order={})", stage.getStageName(), stage.getOrder());
            stage.process(context, this);
        }
        if (broken) {
            log.info("[DefaultChain] Chain broken at stage {} reason: {}",
                    getCurrentStage() >= 0 && getCurrentStage() < stages.size()
                            ? stages.get(getCurrentStage()).getStageName() : "unknown",
                    breakReason != null ? breakReason : "explicit break");
        }
    }

    /**
     * 通知链进入下一阶段。如果链已断裂则抛出异常。
     *
     * @param context 聊天上下文
     * @throws IllegalStateException 如果链已断裂
     */
    @Override
    public void next(ChatContext context) {
        if (broken) {
            throw new IllegalStateException("Chain has been broken");
        }
    }

    /**
     * 从上下文属性中获取断裂原因并中断链。
     *
     * @param context 聊天上下文
     */
    @Override
    public void breakChain(ChatContext context) {
        this.broken = true;
        Object reasonAttr = context.getAttribute("__chain_break_reason__");
        if (reasonAttr instanceof String s) {
            this.breakReason = s;
        }
    }

    /**
     * 以指定的原因中断链。
     *
     * @param context 聊天上下文
     * @param reason  断裂原因
     */
    public void breakChain(ChatContext context, String reason) {
        this.breakReason = reason;
        this.broken = true;
        context.setAttribute("__chain_break_reason__", reason);
    }

    @Override
    public int getCurrentStage() {
        return currentIndex - 1;
    }

    public boolean isBroken() {
        return broken;
    }

    public String getBreakReason() {
        return breakReason;
    }
}
