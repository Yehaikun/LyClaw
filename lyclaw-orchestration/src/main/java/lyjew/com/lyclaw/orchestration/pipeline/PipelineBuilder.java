package lyjew.com.lyclaw.orchestration.pipeline;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.pipeline.Pipeline;
import lyjew.com.lyclaw.pipeline.PipelineStage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
public class PipelineBuilder {

    private final List<PipelineStage> allStages;
    private Pipeline pipeline;

    public PipelineBuilder(List<PipelineStage> allStages) {
        allStages.sort(Comparator.comparingInt(PipelineStage::getOrder));
        this.allStages = allStages;
        this.pipeline = new DefaultPipeline(new ArrayList<>(allStages));
        log.info("[PipelineBuilder] Auto-discovered {} PipelineStage(s), built Pipeline:", allStages.size());
        for (PipelineStage stage : allStages) {
            log.info("  -- [{}] order={}", stage.getStageName(), stage.getOrder());
        }
    }

    public Pipeline build() {
        return pipeline;
    }

    public Pipeline rebuild() {
        List<PipelineStage> sorted = new ArrayList<>(allStages);
        sorted.sort(Comparator.comparingInt(PipelineStage::getOrder));
        this.pipeline = new DefaultPipeline(sorted);
        log.info("[PipelineBuilder] Pipeline rebuilt with {} stages", sorted.size());
        return pipeline;
    }

    public List<PipelineStage> getStages() {
        return new ArrayList<>(allStages);
    }

    public int getStageCount() {
        return allStages.size();
    }
}
