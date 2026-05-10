package lyjew.com.lyclaw.orchestration.pipeline;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.pipeline.Pipeline;
import lyjew.com.lyclaw.pipeline.PipelineStage;

import java.util.List;

@Slf4j
public class DefaultPipeline implements Pipeline {

    private final List<PipelineStage> stages;

    DefaultPipeline(List<PipelineStage> stages) {
        this.stages = List.copyOf(stages);
        log.info("[DefaultPipeline] Initialized with {} stages", stages.size());
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
