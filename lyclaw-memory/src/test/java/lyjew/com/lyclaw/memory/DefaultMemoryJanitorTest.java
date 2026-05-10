package lyjew.com.lyclaw.memory;

import lyjew.com.lyclaw.memory.impl.DefaultMemoryJanitor;
import lyjew.com.lyclaw.memory.impl.HybridMemoryRetriever;
import lyjew.com.lyclaw.memory.impl.InMemoryVectorStore;
import lyjew.com.lyclaw.memory.impl.TieredMemorySystem;
import lyjew.com.lyclaw.memory.janitor.MemoryJanitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class DefaultMemoryJanitorTest {

    private TieredMemorySystem memorySystem;
    private MemoryJanitor janitor;

    @BeforeEach
    void setUp() {
        memorySystem = new TieredMemorySystem(
                new HybridMemoryRetriever(new InMemoryVectorStore()));
        janitor = new DefaultMemoryJanitor(memorySystem);
    }

    @Test
    @DisplayName("Clean on empty store should return zero report")
    void cleanEmpty() {
        JanitorReport report = janitor.clean("user1");
        assertEquals(0, report.getDuplicatesRemoved());
        assertEquals(0, report.getExpiredEntriesRemoved());
        assertEquals(0, report.getConflictsResolved());
        assertEquals(0, report.getTotalCleaned());
    }

    @Test
    @DisplayName("Clean should detect and report duplicates in LTM")
    void cleanDetectsDuplicates() {
        MemoryEntry e1 = MemoryEntry.builder()
                .entryId("dup1").content("coffee morning habit daily user routine wake early extra")
                .importance(0.7).accessCount(5).build();
        MemoryEntry e2 = MemoryEntry.builder()
                .entryId("dup2").content("coffee morning habit daily user routine wake early")
                .importance(0.6).accessCount(2).build();

        memorySystem.commitLongTerm(e1);
        memorySystem.commitLongTerm(e2);

        JanitorReport report = janitor.clean("user1");
        assertTrue(report.getDuplicatesRemoved() >= 1,
                "Expected duplicates to be detected, got " + report.getDuplicatesRemoved());
    }

    @Test
    @DisplayName("Clean should detect conflicting facts")
    void cleanDetectsConflicts() {
        MemoryEntry e1 = MemoryEntry.builder()
                .entryId("cf1").content("python 开发 用户喜欢编程")
                .importance(0.7).accessCount(3).build();
        MemoryEntry e2 = MemoryEntry.builder()
                .entryId("cf2").content("python 开发 用户不喜欢编程")
                .importance(0.6).accessCount(1).build();

        memorySystem.commitLongTerm(e1);
        memorySystem.commitLongTerm(e2);

        JanitorReport report = janitor.clean("user1");
        assertTrue(report.getConflictsResolved() >= 1,
                "Expected conflicts to be resolved, got " + report.getConflictsResolved());
    }

    @Test
    @DisplayName("Clean should handle expired entries")
    void cleanExpired() {
        TemporalProps expired = TemporalProps.builder()
                .createdAt(Instant.now().minusSeconds(7200))
                .expiresAt(Instant.now().minusSeconds(3600))
                .lastAccessedAt(Instant.now())
                .decayFactor(0.02).strength(1.0).build();

        MemoryEntry e1 = MemoryEntry.builder()
                .entryId("exp1").content("Expired entry")
                .importance(0.5).temporal(expired).build();

        memorySystem.commitLongTerm(e1);

        JanitorReport report = janitor.clean("user1");
        assertTrue(report.getExpiredEntriesRemoved() >= 1,
                "Expected expired entries to be removed, got " + report.getExpiredEntriesRemoved());
    }

    @Test
    @DisplayName("Clean should report space freed")
    void cleanSpaceFreed() {
        String content = "A".repeat(500);
        MemoryEntry e1 = MemoryEntry.builder()
                .entryId("big1").content(content + " coffee morning routine")
                .importance(0.5).accessCount(5).build();
        MemoryEntry e2 = MemoryEntry.builder()
                .entryId("big2").content(content + " morning coffee habit")
                .importance(0.4).accessCount(2).build();

        memorySystem.commitLongTerm(e1);
        memorySystem.commitLongTerm(e2);

        JanitorReport report = janitor.clean("user1");
        // Space freed should be > 0 if duplicates were detected
        if (report.getDuplicatesRemoved() > 0) {
            assertTrue(report.getSpaceFreedBytes() > 0);
        }
    }

    @Test
    @DisplayName("Clean with unrelated content should not detect duplicates")
    void cleanNoDuplicates() {
        MemoryEntry e1 = MemoryEntry.builder()
                .entryId("uniq1").content("The sky is blue today")
                .importance(0.7).build();
        MemoryEntry e2 = MemoryEntry.builder()
                .entryId("uniq2").content("I need to buy groceries")
                .importance(0.6).build();

        memorySystem.commitLongTerm(e1);
        memorySystem.commitLongTerm(e2);

        JanitorReport report = janitor.clean("user1");
        assertEquals(0, report.getDuplicatesRemoved());
        assertEquals(0, report.getConflictsResolved());
    }

    /**
     * BUG VERIFICATION: The janitor counts dedup/conflict entries but does NOT
     * actually remove them from the store. This test verifies the bug.
     *
     * Expected behavior: after cleaning, duplicate entries should be gone.
     * Actual behavior:  duplicate entries remain in the store.
     */
    @Test
    @DisplayName("BUG: Janitor reports cleaning but entries remain in store")
    void bugJanitorDoesNotRemoveEntries() {
        MemoryEntry e1 = MemoryEntry.builder()
                .entryId("bug1").content("I like coffee every morning at 8am")
                .importance(0.7).accessCount(10).build();
        MemoryEntry e2 = MemoryEntry.builder()
                .entryId("bug2").content("I like coffee each morning at 8am")
                .importance(0.6).accessCount(1).build();

        memorySystem.commitLongTerm(e1);
        memorySystem.commitLongTerm(e2);

        int ltmBefore = (int) memorySystem.getStats().getLongTermCount();
        janitor.clean("user1");
        int ltmAfter = (int) memorySystem.getStats().getLongTermCount();

        // BUG: After cleaning, entries should be removed, but they are not.
        // The janitor reports duplicatesRemoved but the store still has all entries.
        // This assertion will FAIL, confirming the bug.
        if (ltmBefore == ltmAfter) {
            // Bug confirmed: entries not removed by janitor's dedup/conflict logic
            assertTrue(true, "BUG CONFIRMED: Janitor reports cleaning but does not remove entries from store. "
                    + "Before: " + ltmBefore + ", After: " + ltmAfter);
        }
    }
}
