package lyjew.com.lyclaw.memory;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class JanitorReport {
    private int duplicatesRemoved;
    private int expiredEntriesRemoved;
    private int conflictsResolved;
    private int totalCleaned;
    private long durationMs;
    private long spaceFreedBytes;
}
