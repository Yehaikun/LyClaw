package lyjew.com.lyclaw.memory.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.memory.MemoryManager;
import lyjew.com.lyclaw.memory.MemoryStrategy;
import lyjew.com.lyclaw.storage.MemoryStorage;
import lyjew.com.lyclaw.model.Memory;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 基于文件的记忆管理器 —— 通过 MemoryStorage（lyclaw-storage 模块）持久化记忆。
 *
 * <p>Memory 实体 id 固定为 "global"，每次操作通过 BaseStorage.get/save 完成。
 * 启动时从文件反序列化恢复，运行时修改后序列化写回。</p>
 *
 * <p><b>为什么用 MemoryStorage 而不是直接读写文件</b>：
 * memory/impl 属于 engine 层，不应该知道文件路径、序列化格式等底层细节。
 * 通过 MemoryStorage（继承 BaseStorage），engine 层只管业务逻辑。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see MemoryManager
 * @see MemoryStorage
 * @see Memory
 */
@Slf4j
@Component
@org.springframework.context.annotation.Primary
public class FileMemoryManager implements MemoryManager {

    /** 全局记忆的固定 id */
    private static final String GLOBAL_MEMORY_ID = "global";

    /** 文件存储接口 —— 由 lyclaw-storage 模块提供 */
    private final MemoryStorage storage;

    /** 当前记忆内容（内存快照，由 Memory 转换而来） */
    private MemoryContent current;

    /**
     * 构造 FileMemoryManager，从文件系统加载记忆。
     *
     * @param storage MemoryStorage 实例（由 Spring 注入）
     */
    public FileMemoryManager(MemoryStorage storage) {
        this.storage = storage;
        // 启动时从文件加载记忆；无文件时创建空内容
        this.current = loadFromStorage();
    }

    /**
     * 从文件存储加载记忆，无文件时返回空内容。
     */
    private MemoryContent loadFromStorage() {
        Optional<Memory> opt = storage.get(GLOBAL_MEMORY_ID);
        if (opt.isPresent()) {
            Memory mem = opt.get();
            log.debug("加载长期记忆成功！");
            return new MemoryContent(
                    mem.getContent(), "file", mem.isEnabled(),
                    mem.getTags(), 0.0
            );
        }
        return new MemoryContent("", "file", true, Collections.emptyList(), 0.0);
    }

    /**
     * 将内存中的 MemoryContent 写回文件存储。
     *
     * <p>由持久化决策层通过 {@link PersistenceExecutor} 触发，
     * 不再每次 append 后自动调用。</p>
     */
    @Override
    public void flush() {
        Memory mem = Memory.builder()
                .id(GLOBAL_MEMORY_ID)
                .content(current.getContent())
                .enabled(true)
                .tags(current.getTags())
                .build();
        storage.save(mem);
        log.info("FileMemoryManager.flush: 已写盘 ({} 字节)", current.getContent().length());
    }

    @Override
    public MemoryContent read() {
        log.debug("读取记忆{}" , current);
        return current;
    }

    /**
     * 追加记忆内容。追加前自动提取纯文本（如果 content 包含 SSE JSON 格式则只取文本部分）。
     *
     * <p>不再自动调用 persist()，持久化时机由持久化决策层控制。
     * 外部通过 {@link #flush()} 触发写盘。</p>
     */
    @Override
    public void append(String content) {
        String clean = extractPlainText(content);
        log.info("FileMemoryManager.append: contentLen={} cleanLen={}", content.length(), clean.length());
        String newContent = current.getContent() + "\n" + clean;
        current = new MemoryContent(
                newContent, "file", true, current.getTags(), 0.0
        );
        // 不再自动 persist()，由持久化决策层控制刷盘时机
    }

    /**
     * 从字符串中提取纯文本，过滤掉 SSE JSON 格式数据。
     * 如果内容包含 "data:{" 格式（SSE JSON），提取所有 delta.content 的值。
     * 否则原样返回。
     */
    private String extractPlainText(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        // 如果内容不含 SSE JSON 特征（"choices" 和 "delta" 同时出现），直接返回
        if (!raw.contains("data:") && !raw.contains("choices")) {
            return raw;
        }
        StringBuilder text = new StringBuilder();
        // 提取 "content":"xxx" 中的 xxx，处理转义字符
        Pattern p = Pattern.compile("\"content\":\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = p.matcher(raw);
        boolean found = false;
        while (m.find()) {
            String val = m.group(1);
            if (val != null && !val.isEmpty()) {
                text.append(val);
                found = true;
            }
        }
        if (found) {
            return text.toString();
        }
        // 没有找到 content 字段，返回原始内容（可能不是 SSE）
        return raw;
    }

    @Override
    public void rewrite(String content) {
        current = new MemoryContent(
                content, "file", true, Collections.emptyList(), 0.0
        );
        flush();
    }

    @Override
    public List<MemoryContent> search(String query) {
        if (current.getContent().contains(query)) {
            return List.of(current);
        }
        return Collections.emptyList();
    }

    @Override
    public MemoryStrategy getStrategy() {
        return null;
    }

    @Override
    public void setStrategy(MemoryStrategy strategy) {
    }
}