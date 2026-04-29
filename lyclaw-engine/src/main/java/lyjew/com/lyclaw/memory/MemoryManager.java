package lyjew.com.lyclaw.memory;

import java.util.List;

/**
 * 长期记忆管理器接口 —— 管理 AI 助手的长期记忆。
 *
 * <p>长期记忆是 AI 助手在多次对话之间保持的知识。
 * 例如用户告诉 AI "我的名字是海坤"、"我住在北京"，
 * 这些信息被写入长期记忆后，在未来的对话中会自动注入上下文。</p>
 *
 * <p><b>持久化说明</b>：MemoryManager 本身不处理文件读写，
 * 实际读写委托给 {@code lyjew.com.lyclaw.storage.MemoryStorage}。
 * 实体来源：{@code lyjew.com.lyclaw.model.Memory}（单例 id="global"）。</p>
 *
 * <p><b>设计动机</b>：如果没有记忆管理器，每次对话都是独立的，
 * AI 不会记得用户之前说过的重要信息。MemoryManager 让 AI 具备了"记忆力"。
 * 通过可切换的 MemoryStrategy，还可以控制记忆注入上下文的方式。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see MemoryContent
 * @see MemoryStrategy
 */
public interface MemoryManager {

    /**
     * 读取长期记忆（单例 global）。
     *
     * @return 长期记忆内容包装对象
     */
    MemoryContent read();

    /**
     * 追加记忆内容。通常用于在对话结束后提取关键信息追加到记忆末尾。
     *
     * @param content 要追加的记忆内容（Markdown 格式）
     */
    void append(String content);

    /**
     * 重写整条记忆。用新的内容替换整条记忆。
     * 谨慎使用，会丢失原有记忆。
     *
     * @param content 新的记忆内容
     */
    void rewrite(String content);

    /**
     * 搜索记忆。根据查询条件匹配记忆内容中的关键字。
     *
     * @param query 搜索查询
     * @return 匹配的记忆内容列表
     */
    List<MemoryContent> search(String query);

    /**
     * 获取当前记忆策略。
     *
     * @return 当前使用的记忆策略
     */
    MemoryStrategy getStrategy();

    /**
     * 切换记忆策略。运行时动态改变记忆注入上下文的方式。
     *
     * @param strategy 新的记忆策略
     */
    void setStrategy(MemoryStrategy strategy);
}