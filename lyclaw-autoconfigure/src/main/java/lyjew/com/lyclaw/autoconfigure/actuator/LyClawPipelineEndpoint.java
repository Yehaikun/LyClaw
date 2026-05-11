package lyjew.com.lyclaw.autoconfigure.actuator;

import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes pipeline stage order and metadata.
 */
@Endpoint(id = "lyclaw-pipeline")
public class LyClawPipelineEndpoint {

    private final List<ReactivePipelineStage> stages;

    @Autowired
    public LyClawPipelineEndpoint(@Autowired(required = false) List<ReactivePipelineStage> stages) {
        this.stages = stages;
    }

    @ReadOperation
    public Map<String, Object> pipeline() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (stages == null || stages.isEmpty()) {
            result.put("available", false);
            result.put("reason", "No ReactivePipelineStage beans discovered");
            return result;
        }
        result.put("stageCount", stages.size());
        result.put("stages", stages.stream()
                .sorted(java.util.Comparator.comparingInt(ReactivePipelineStage::getOrder))
                .map(stage -> {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("name", stage.getStageName());
                    s.put("order", stage.getOrder());
                    s.put("class", stage.getClass().getName());
                    return s;
                })
                .toList());
        return result;
    }
}
