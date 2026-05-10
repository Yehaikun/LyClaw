package lyjew.com.lyclaw.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryQueryResult {
    private List<MemoryEntry> entries;
    private int totalHits;
    private long queryTimeMs;
    private String retrievalMethod;
}
