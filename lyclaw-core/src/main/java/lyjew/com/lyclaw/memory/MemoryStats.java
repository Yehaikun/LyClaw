package lyjew.com.lyclaw.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
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
