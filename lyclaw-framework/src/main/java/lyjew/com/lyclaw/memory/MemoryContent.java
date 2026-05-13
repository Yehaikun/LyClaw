package lyjew.com.lyclaw.memory;

import java.util.List;

/**
 * 记忆内容封装类（Memory Content），是 LyClaw 记忆系统中用于表示单条持久化记忆的数据载体。
 *
 * <p>在 LyClaw 框架的记忆管理体系中，MemoryContent 代表了从记忆存储中检索出的一条具体
 * 记忆记录。它封装了记忆的核心属性：完整的文本内容（content）、标题摘要（title）、
 * 启用状态（enabled，控制该记忆是否在上下文注入时被使用）、分类标签（tags，支持按标签
 * 进行记忆的检索和过滤）以及与最近一次查询的相关性评分（relevanceScore，用于排序和
 * 筛选最相关的记忆条目）。这些属性共同支撑了框架的记忆检索、过滤和上下文注入机制。
 *
 * <p>本类设计为不可变对象（Immutable Object）：所有字段通过唯一的一个全参数构造器注入，
 * 不提供任何 setter 方法，仅提供 getter 方法读取字段值。这种不可变设计确保了记忆内容
 * 在多个组件间共享时不会被意外修改，简化了并发场景下的使用，也使得记忆的版本追踪和
 * 缓存更安全。构造器本身对参数不做任何校验或转换，由调用者确保传入数据的正确性。
 *
 * <p>记忆的生命周期：在典型的对话处理流程中，框架的记忆检索组件会根据用户的当前提问
 * 从存储后端（文件系统、向量数据库等）中检索相关记忆，将检索结果包装为 MemoryContent
 * 列表，设置相应的 relevanceScore 评分后注入到 {@link lyjew.com.lyclaw.context.ChatContext}
 * 中。上下文构建器随后将这些记忆内容格式化后拼接到 AI 模型的提示词中，实现跨会话的
 * 信息持久化和个性化对话体验。
 *
 * <p>便捷方法：{@link #empty()} 静态工厂方法创建一个所有字段均为默认值的安全占位实例
 * （content=""、title=""、enabled=true、tags=空列表、relevanceScore=0.0），用于
 * 初始化、默认填充和空值安全的场景，避免在记忆系统未启用或检索无结果时出现空指针异常。
 *
 * @see lyjew.com.lyclaw.context.ChatContext
 * @see lyjew.com.lyclaw.memory.MemoryEntry
 */
public class MemoryContent {

    private final String content;
    private final String title;
    private final boolean enabled;
    private final List<String> tags;
    private final double relevanceScore;

    /**
     * 构造一个记忆内容对象。
     *
     * @param content        完整文本内容
     * @param title          内容标题
     * @param enabled        是否启用（false 则在上下文中跳过此内容）
     * @param tags           分类标签列表
     * @param relevanceScore 与查询的相关性评分
     */
    public MemoryContent(String content, String title, boolean enabled,
                         List<String> tags, double relevanceScore) {
        this.content = content;
        this.title = title;
        this.enabled = enabled;
        this.tags = tags;
        this.relevanceScore = relevanceScore;
    }

    /** @return 记忆的完整文本内容 */
    public String getContent() { return content; }

    /** @return 记忆的标题 */
    public String getTitle() { return title; }

    /** @return 是否启用，false 表示该记忆在上下文注入时被忽略 */
    public boolean isEnabled() { return enabled; }

    /** @return 记忆的分类标签 */
    public List<String> getTags() { return tags; }

    /** @return 与最近一次查询的相关性评分 */
    public double getRelevanceScore() { return relevanceScore; }

    /**
     * 创建一个空的记忆内容占位对象。
     *
     * 用于初始化或填充默认值场景，所有字段为默认值。
     *
     * @return 内容为空、标题为空、启用、无标签、评分为零的占位实例
     */
    public static MemoryContent empty() {
        return new MemoryContent("", "", true, List.of(), 0.0);
    }
}
