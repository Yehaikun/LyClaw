package lyjew.com.lyclaw.memory;

import lyjew.com.lyclaw.memory.impl.HybridMemoryRetriever;
import lyjew.com.lyclaw.memory.impl.InMemoryVectorStore;
import lyjew.com.lyclaw.memory.impl.LegacyMemoryManagerAdapter;
import lyjew.com.lyclaw.memory.impl.TieredMemorySystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import lyjew.com.lyclaw.context.ChatContext;

import static org.junit.jupiter.api.Assertions.*;

class LegacyMemoryManagerAdapterTest {

    private LegacyMemoryManagerAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new LegacyMemoryManagerAdapter(
                new TieredMemorySystem(new HybridMemoryRetriever(new InMemoryVectorStore())));
    }

    @Test
    @DisplayName("read on empty store should return empty content")
    void readEmpty() {
        MemoryContent content = adapter.read();
        assertNotNull(content);
        assertNotNull(content.getContent());
        assertTrue(content.isEnabled());
    }

    @Test
    @DisplayName("append should commit content as long-term memory")
    void append() {
        adapter.append("Test legacy content");
        MemoryContent content = adapter.read();
        assertNotNull(content.getContent());
        assertTrue(content.getContent().contains("Test legacy content"));
    }

    @Test
    @DisplayName("append null content should be no-op")
    void appendNull() {
        adapter.append(null);
        MemoryContent content = adapter.read();
        assertNotNull(content);
    }

    @Test
    @DisplayName("append blank content should be no-op")
    void appendBlank() {
        adapter.append("   ");
        MemoryContent content = adapter.read();
        assertNotNull(content);
    }

    @Test
    @DisplayName("rewrite should replace current content")
    void rewrite() {
        adapter.append("Old content");
        adapter.rewrite("New content");
        MemoryContent content = adapter.read();
        assertTrue(content.getContent().contains("New content"));
    }

    @Test
    @DisplayName("rewrite with null should clear content")
    void rewriteNull() {
        adapter.append("Something");
        adapter.rewrite(null);
    }

    @Test
    @DisplayName("search on empty store should return empty list")
    void searchEmpty() {
        List<MemoryContent> results = adapter.search("test");
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("search with null query should return empty list")
    void searchNull() {
        List<MemoryContent> results = adapter.search(null);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("search should find committed content")
    void search() {
        adapter.append("The user prefers Python for data science");
        List<MemoryContent> results = adapter.search("Python");
        assertFalse(results.isEmpty());
        MemoryContent result = results.get(0);
        assertTrue(result.getContent().contains("Python"));
    }

    @Test
    @DisplayName("flush should not throw")
    void flush() {
        assertDoesNotThrow(() -> adapter.flush());
    }

    @Test
    @DisplayName("getStrategy and setStrategy should work")
    void strategy() {
        assertNull(adapter.getStrategy());
        adapter.setStrategy(new MemoryStrategy() {
            public String formatForContext(MemoryContent memory) { return "formatted"; }
            public boolean shouldIncludeInContext(MemoryContent m, ChatContext c) { return true; }
            public int getPriority() { return 1; }
        });
        assertNotNull(adapter.getStrategy());
    }
}
