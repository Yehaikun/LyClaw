package lyjew.com.lyclaw.memory;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MemoryConsolidationPolicy {
    private double importanceThreshold = 0.7;
    private double dedupThreshold = 0.85;
    private boolean llmDrivenSummary = true;
    private int maxBatchSize = 100;
}
