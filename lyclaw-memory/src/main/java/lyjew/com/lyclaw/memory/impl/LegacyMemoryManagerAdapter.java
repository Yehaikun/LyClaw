package lyjew.com.lyclaw.memory.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.*;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 遗留内存管理器适配器，将旧版 MemoryManager 接口桥接到新的 MemorySystem 架构。
 *
 * <p>该类作为兼容层，允许旧版代码通过 MemoryManager 接口操作新的三层内存系统
 * （感知层/短期记忆层/长期记忆层）。它维护一个本地 {@code currentContent} 缓冲区，
 * 同时将所有写入持久化到长期记忆层中。</p>
 *
 * <p>设计动机：在系统从单层内存架构迁移到多层内存架构的过程中，
 * 旧版 Agent 组件仍然依赖 MemoryManager 接口。该适配器确保平滑过渡，
 * 避免大规模代码重构。</p>
 */
@Slf4j
@Component
public class LegacyMemoryManagerAdapter implements MemoryManager {

    /** 旧版系统中使用的固定用户ID */
    private static final String LEGACY_USER_ID = "legacy";
    /** 旧版系统中使用的固定会话ID */
    private static final String LEGACY_SESSION_ID = "legacy-global";

    private final MemorySystem memorySystem;
    private MemoryStrategy strategy;
    /** 本地内存缓冲区，保存最近一次写入的内容 */
    private String currentContent = "";

    /**
     * 构造函数，注入内存系统实例。
     *
     * @param memorySystem 底层多层内存系统
     */
    public LegacyMemoryManagerAdapter(MemorySystem memorySystem) {
        this.memorySystem = memorySystem;
    }

    /**
     * 读取长期记忆，返回格式化的记忆内容。
     *
     * <p>从长期记忆层检索最多100条记录，合并为 Markdown 格式的列表，
     * 并追加本地缓冲区中的当前内容。</p>
     *
     * @return 包含格式化记忆文本的 MemoryContent
     */
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

    /**
     * 追加内容到当前缓冲区并持久化到长期记忆。
     *
     * @param content 要追加的文本内容，空值或空白内容会被忽略
     */
    @Override
    public void append(String content) {
        if (content == null || content.isBlank()) return;

        log.info("LegacyMemoryManagerAdapter.append: appending {} chars", content.length());
        // 追加到本地缓冲区
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

    /**
     * 用新内容完全覆盖当前缓冲区并持久化到长期记忆。
     *
     * @param content 新的文本内容，如果为 null 则清空缓冲区
     */
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

    /**
     * 搜索记忆条目，跨长期记忆和短期记忆层检索匹配内容。
     *
     * @param query 搜索查询文本
     * @return 匹配的记忆内容列表，查询为空时返回空列表
     */
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
