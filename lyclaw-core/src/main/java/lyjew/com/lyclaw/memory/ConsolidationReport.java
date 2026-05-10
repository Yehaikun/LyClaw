package lyjew.com.lyclaw.memory;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ConsolidationReport {
    private int promotedToLongTerm;
    private int mergedDuplicates;
    private int expiredRemoved;
    private int totalProcessed;
    private long durationMs;
    private List<String> promotedEntryIds;
}
