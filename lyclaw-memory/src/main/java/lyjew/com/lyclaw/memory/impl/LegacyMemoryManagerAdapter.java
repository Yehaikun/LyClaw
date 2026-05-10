package lyjew.com.lyclaw.memory.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.*;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class LegacyMemoryManagerAdapter implements MemoryManager {

    private static final String LEGACY_USER_ID = "legacy";
    private static final String LEGACY_SESSION_ID = "legacy-global";

    private final MemorySystem memorySystem;
    private MemoryStrategy strategy;
    private String currentContent = "";

    public LegacyMemoryManagerAdapter(MemorySystem memorySystem) {
        this.memorySystem = memorySystem;
    }

    @Override
    public MemoryContent read() {
        MemoryQuery query = MemoryQuery.builder()
                .topK(100)
                .layerFilter(List.of(MemoryLayerType.LONG_TERM))
                .build();

        MemoryQueryResult result = memorySystem.retrieve(query);
        List<MemoryEntry> entries = result.getEntries();

        if (entries.isEmpty()) {
            return new MemoryContent(currentContent, "Long-Term Memory", true, Collections.emptyList(), 0.0);
        }

        StringBuilder sb = new StringBuilder("# Long-Term Memory\n\n");
        for (MemoryEntry entry : entries) {
            if (entry.getContent() != null && !entry.getContent().isBlank()) {
                sb.append("- ").append(entry.getContent()).append("\n");
            }
        }
        sb.append("\n").append(currentContent);

        String content = sb.toString();
        log.debug("LegacyMemoryManagerAdapter.read: {} entries, {} chars", entries.size(), content.length());

        return new MemoryContent(content, "Long-Term Memory", true, Collections.emptyList(), 0.0);
    }

    @Override
    public void append(String content) {
        if (content == null || content.isBlank()) return;

        log.info("LegacyMemoryManagerAdapter.append: appending {} chars", content.length());
        currentContent = currentContent + "\n" + content;

        PerceptionData data = PerceptionData.builder()
                .role("system").content(content)
                .timestamp(System.currentTimeMillis())
                .toolCallIds(Collections.emptyList())
                .metadata(Collections.emptyMap())
                .build();

        MemoryEntry entry = memorySystem.ingestPerception(LEGACY_SESSION_ID, data);
        entry.setUserId(LEGACY_USER_ID);
        memorySystem.commitLongTerm(entry);
    }

    @Override
    public void rewrite(String content) {
        log.info("LegacyMemoryManagerAdapter.rewrite: rewriting with {} chars",
                content != null ? content.length() : 0);
        currentContent = (content != null) ? content : "";
        if (content != null && !content.isBlank()) {
            PerceptionData data = PerceptionData.builder()
                    .role("system").content(content)
                    .timestamp(System.currentTimeMillis())
                    .toolCallIds(Collections.emptyList())
                    .metadata(Collections.emptyMap())
                    .build();

            MemoryEntry entry = memorySystem.ingestPerception(LEGACY_SESSION_ID, data);
            entry.setUserId(LEGACY_USER_ID);
            memorySystem.commitLongTerm(entry);
        }
    }

    @Override
    public List<MemoryContent> search(String query) {
        if (query == null || query.isBlank()) return Collections.emptyList();

        log.debug("LegacyMemoryManagerAdapter.search: query={}", query);

        MemoryQuery memQuery = MemoryQuery.builder()
                .queryText(query).topK(20)
                .layerFilter(List.of(MemoryLayerType.LONG_TERM, MemoryLayerType.SHORT_TERM))
                .build();

        MemoryQueryResult result = memorySystem.retrieve(memQuery);

        return result.getEntries().stream()
                .map(e -> new MemoryContent(e.getContent(), "Search Result", true, e.getTags(), e.getImportance()))
                .collect(Collectors.toList());
    }

    @Override
    public void flush() {
        log.debug("LegacyMemoryManagerAdapter.flush: no-op (in-memory storage)");
    }

    @Override
    public MemoryStrategy getStrategy() { return strategy; }

    @Override
    public void setStrategy(MemoryStrategy strategy) {
        log.debug("LegacyMemoryManagerAdapter.setStrategy: strategy changed to {}",
                strategy != null ? strategy.getClass().getSimpleName() : "null");
        this.strategy = strategy;
    }
}
