package lyjew.com.lyclaw.memory;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class MemoryQueryResult {

    private List<MemoryEntry> entries;
    private int totalHits;
    private long queryTimeMs;
    private String retrievalMethod;
}
