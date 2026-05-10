package lyjew.com.lyclaw.memory;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MemoryStats {

    private long perceptionCount;
    private long shortTermCount;
    private long longTermCount;
    private long entityCount;
    private long totalTokens;
    private double avgImportance;
    private long lastConsolidationTime;
    private long lastJanitorRunTime;
}
