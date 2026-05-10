package lyjew.com.lyclaw.orchestration.pipeline;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;

import java.util.List;

@Slf4j
public class DefaultChain implements Chain {

    private final List<PipelineStage> stages;
    private int currentIndex;
    private boolean broken = false;
    private String breakReason;

    public DefaultChain(List<PipelineStage> stages, int startIndex) {
        this.stages = stages;
        this.currentIndex = startIndex;
    }

    public void proceed(ChatContext context) {
        while (currentIndex < stages.size() && !broken) {
            PipelineStage stage = stages.get(currentIndex);
            currentIndex++;
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

    @Override
    public void next(ChatContext context) {
        if (broken) {
            throw new IllegalStateException("Chain has been broken");
        }
    }

    @Override
    public void breakChain(ChatContext context) {
        this.broken = true;
        Object reasonAttr = context.getAttribute("__chain_break_reason__");
        if (reasonAttr instanceof String s) {
            this.breakReason = s;
        }
    }

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
