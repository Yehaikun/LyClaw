package lyjew.com.lyclaw.memory;

import lyjew.com.lyclaw.memory.impl.LLMMemoryExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LLMMemoryExtractorTest {

    private LLMMemoryExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new LLMMemoryExtractor();
    }

    @Test
    @DisplayName("Extract from empty conversation should return empty list")
    void extractEmptyConversation() {
        List<MemoryEntry> entries = extractor.extract("", Collections.emptyList());
        assertTrue(entries.isEmpty());
    }

    @Test
    @DisplayName("Extract from null conversation should return empty list")
    void extractNullConversation() {
        List<MemoryEntry> entries = extractor.extract(null, Collections.emptyList());
        assertTrue(entries.isEmpty());
    }

    @Test
    @DisplayName("Extract should detect preferences")
    void extractPreferences() {
        String conversation = "我喜欢喝咖啡不加糖，每天早上都会来一杯。";
        List<MemoryEntry> entries = extractor.extract(conversation, Collections.emptyList());
        assertTrue(entries.size() >= 1, "Should extract at least 1 preference entry");
        boolean hasPreference = entries.stream()
                .anyMatch(e -> e.getCategory() == MemoryCategory.PREFERENCE);
        assertTrue(hasPreference, "Should detect PREFERENCE category");
    }

    @Test
    @DisplayName("Extract should detect tasks")
    void extractTasks() {
        String conversation = "我需要完成这个项目的文档编写工作并在周五前提交。";
        List<MemoryEntry> entries = extractor.extract(conversation, Collections.emptyList());
        boolean hasTask = entries.stream()
                .anyMatch(e -> e.getCategory() == MemoryCategory.TASK);
        assertTrue(hasTask, "Should detect TASK category");
    }

    @Test
    @DisplayName("Extract should detect facts")
    void extractFacts() {
        String conversation = "当前这个开发项目属于教育技术领域，目前已经开发了三个月。";
        List<MemoryEntry> entries = extractor.extract(conversation, Collections.emptyList());
        assertFalse(entries.isEmpty());
    }

    @Test
    @DisplayName("Extract should detect goals")
    void extractGoals() {
        String conversation = "我的目标是完成系统重构并在Q3上线新版本。";
        List<MemoryEntry> entries = extractor.extract(conversation, Collections.emptyList());
        boolean hasGoal = entries.stream()
                .anyMatch(e -> e.getCategory() == MemoryCategory.GOAL);
        assertTrue(hasGoal, "Should detect GOAL category");
    }

    @Test
    @DisplayName("Extract should set correct entry fields")
    void extractEntryFields() {
        String conversation = "我需要学习编程的基础知识";
        List<MemoryEntry> entries = extractor.extract(conversation, Collections.emptyList());
        assertFalse(entries.isEmpty());

        MemoryEntry entry = entries.get(0);
        assertNotNull(entry.getEntryId());
        assertEquals(MemoryLayerType.SHORT_TERM, entry.getLayer());
        assertTrue(entry.getImportance() >= 0.0 && entry.getImportance() <= 1.0);
        assertEquals(0, entry.getAccessCount());
        assertNotNull(entry.getTags());
        assertTrue(entry.getTags().contains("auto-extracted"));
        assertNotNull(entry.getTemporal());
        assertEquals(0.05, entry.getTemporal().getDecayFactor(), 1e-6);
    }

    @Test
    @DisplayName("Extract should deduplicate against existing memories")
    void extractDedupExisting() {
        MemoryEntry existing = MemoryEntry.builder()
                .entryId("existing-1").content("我需要学习编程的基础知识").build();

        String conversation = "我需要学习编程的基础知识";
        List<MemoryEntry> entries = extractor.extract(conversation, List.of(existing));
        assertTrue(entries.isEmpty(), "Should dedup exact match against existing memories");
    }

    @Test
    @DisplayName("supportsRealtime should return true")
    void supportsRealtime() {
        assertTrue(extractor.supportsRealtime());
    }

    @Test
    @DisplayName("getExtractorName should return heuristic name")
    void getExtractorName() {
        assertTrue(extractor.getExtractorName().contains("LLM"));
    }

    @Test
    @DisplayName("Extract should handle conversation with only stop phrases")
    void extractStopPhrases() {
        String conversation = "你好 谢谢 再见 好的 明白";
        List<MemoryEntry> entries = extractor.extract(conversation, Collections.emptyList());
        // Should be empty or near-empty, as these are stop phrases
        assertTrue(entries.isEmpty() || entries.size() <= 1);
    }

    @Test
    @DisplayName("Extract from complex conversation should produce multiple entries")
    void extractComplexConversation() {
        String conversation = """
            我喜欢在安静的环境中工作。我需要明天完成代码审查。
            这个项目属于金融科技领域。我的目标是本季度完成产品上线。
            上次因为缺少单元测试导致线上故障。""";
        List<MemoryEntry> entries = extractor.extract(conversation, Collections.emptyList());
        assertTrue(entries.size() >= 3,
                "Complex conversation should yield multiple entries, got " + entries.size());
    }
}
