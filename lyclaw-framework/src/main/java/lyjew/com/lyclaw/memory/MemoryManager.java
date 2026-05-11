package lyjew.com.lyclaw.memory;

import java.util.List;

/**
 * 已废弃的记忆管理接口，将于未来版本移除。
 *
 * 该类是早期版本的记忆管理入口，仅支持简单的读写、追加、重写等操作。
 * 新代码应改用功能更完善的 {@link MemorySystem} 接口。
 *
 * @deprecated 自 2.0 起废弃，请使用 {@link MemorySystem} 替代
 */
@Deprecated(since = "2.0", forRemoval = true)
public interface MemoryManager {

    /**
     * 读取当前记忆内容。
     *
     * @return 封装的记忆内容对象
     */
    MemoryContent read();

    /**
     * 向现有记忆追加内容。
     *
     * @param content 待追加的文本内容
     */
    void append(String content);

    /**
     * 用新内容完全替换现有记忆。
     *
     * @param content 新的完整记忆内容
     */
    void rewrite(String content);

    /**
     * 根据查询关键词搜索记忆。
     *
     * @param query 搜索关键词
     * @return 匹配的记忆内容列表
     */
    List<MemoryContent> search(String query);

    /**
     * 清空并持久化当前记忆状态。
     */
    void flush();

    /**
     * 获取当前的记忆格式化策略。
     *
     * @return 记忆策略实例
     */
    MemoryStrategy getStrategy();

    /**
     * 设置记忆格式化策略。
     *
     * @param strategy 新的记忆策略
     */
    void setStrategy(MemoryStrategy strategy);
}
