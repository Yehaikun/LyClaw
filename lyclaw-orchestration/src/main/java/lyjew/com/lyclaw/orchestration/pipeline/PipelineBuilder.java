package lyjew.com.lyclaw.orchestration.pipeline;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.pipeline.Pipeline;
import lyjew.com.lyclaw.pipeline.PipelineContext;
import lyjew.com.lyclaw.pipeline.PipelineStage;
import lyjew.com.lyclaw.pipeline.ReactivePipeline;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
public class PipelineBuilder {

    private final List<PipelineStage> allStages;
    private final List<ReactivePipelineStage> reactiveStages;
    private Pipeline pipeline;
    private ReactivePipeline reactivePipeline;

    public PipelineBuilder(List<PipelineStage> allStages,
                           List<ReactivePipelineStage> reactiveStages) {
        List<PipelineStage> sorted = new ArrayList<>(allStages);
        sorted.sort(Comparator.comparingInt(PipelineStage::getOrder));
        this.allStages = sorted;
        this.pipeline = new DefaultPipeline(new ArrayList<>(sorted));
        log.info("[PipelineBuilder] Auto-discovered {} PipelineStage(s), built Pipeline:", allStages.size());
        for (PipelineStage stage : allStages) {
            log.info("  -- [{}] order={}", stage.getStageName(), stage.getOrder());
        }

        List<ReactivePipelineStage> sortedReactive = new ArrayList<>(reactiveStages);
        sortedReactive.sort(Comparator.comparingInt(ReactivePipelineStage::getOrder));
        this.reactiveStages = sortedReactive;
        this.reactivePipeline = new DefaultReactivePipeline(sortedReactive);
        log.info("[PipelineBuilder] Auto-discovered {} ReactivePipelineStage(s), built ReactivePipeline:", reactiveStages.size());
        for (ReactivePipelineStage stage : reactiveStages) {
            log.info("  -- [{}] order={}", stage.getStageName(), stage.getOrder());
        }
    }

    /** @deprecated use {@link #buildReactive()} for the new reactive pipeline */
    @Deprecated
    public Pipeline build() {
        return pipeline;
    }

    /** @deprecated use {@link #rebuildReactive()} for the new reactive pipeline */
    @Deprecated
    public Pipeline rebuild() {
        List<PipelineStage> sorted = new ArrayList<>(allStages);
        sorted.sort(Comparator.comparingInt(PipelineStage::getOrder));
        this.pipeline = new DefaultPipeline(sorted);
        log.info("[PipelineBuilder] Pipeline rebuilt with {} stages", sorted.size());
        return pipeline;
    }

    public ReactivePipeline buildReactive() {
        return reactivePipeline;
    }

    public ReactivePipeline rebuildReactive() {
        List<ReactivePipelineStage> sorted = new ArrayList<>(reactiveStages);
        sorted.sort(Comparator.comparingInt(ReactivePipelineStage::getOrder));
        this.reactivePipeline = new DefaultReactivePipeline(sorted);
        log.info("[PipelineBuilder] ReactivePipeline rebuilt with {} stages", sorted.size());
        return reactivePipeline;
    }

    public List<PipelineStage> getStages() {
        return new ArrayList<>(allStages);
    }

    public List<ReactivePipelineStage> getReactiveStages() {
        return new ArrayList<>(reactiveStages);
    }

    public int getStageCount() {
        return allStages.size();
    }

    public int getReactiveStageCount() {
        return reactiveStages.size();
    }

    /**
     * Default implementation of {@link ReactivePipeline} that concatenates
     * all stage fluxes. Each stage checks {@link PipelineContext#isTerminated()}
     * and returns {@link Flux#empty()} if the pipeline was cancelled upstream.
     */
    private static class DefaultReactivePipeline implements ReactivePipeline {

        private final List<ReactivePipelineStage> stages;

        DefaultReactivePipeline(List<ReactivePipelineStage> stages) {
            this.stages = List.copyOf(stages);
        }

        @Override
        public Flux<ServerSentEvent<String>> execute(ChatContext context, PipelineContext pipelineCtx) {
            Flux<ServerSentEvent<String>> result = Flux.empty();
            for (ReactivePipelineStage stage : stages) {
                result = result.concatWith(
                        Flux.defer(() -> stage.execute(context, pipelineCtx))
                );
            }
            return result;
        }

        @Override
        public List<ReactivePipelineStage> getStages() {
            return stages;
        }
    }
}
