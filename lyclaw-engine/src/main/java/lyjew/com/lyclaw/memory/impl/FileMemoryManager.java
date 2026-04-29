package lyjew.com.lyclaw.memory.impl;

import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.memory.MemoryManager;
import lyjew.com.lyclaw.memory.MemoryStrategy;
import lyjew.com.lyclaw.storage.MemoryStorage;
import lyjew.com.lyclaw.model.Memory;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
@Component
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
     * <p>每次 append/rewrite 后调用，同步持久化。</p>
     */
    private void persist() {
        Memory mem = Memory.builder()
                .id(GLOBAL_MEMORY_ID)
                .content(current.getContent())
                .enabled(true)
                .tags(current.getTags())
                .build();
        storage.save(mem);
    }

    @Override
    public MemoryContent read() {
        return current;
    }

    @Override
    public void append(String content) {
        String newContent = current.getContent() + "\n" + content;
        current = new MemoryContent(
                newContent, "file", false, current.getTags(), 0.0
        );
        persist();
    }

    @Override
    public void rewrite(String content) {
        current = new MemoryContent(
                content, "file", false, Collections.emptyList(), 0.0
        );
        persist();
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