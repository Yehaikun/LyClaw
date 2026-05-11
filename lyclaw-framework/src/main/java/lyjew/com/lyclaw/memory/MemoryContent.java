package lyjew.com.lyclaw.memory;

import java.util.List;

/**
 * 记忆内容的简单封装类，用于 {@link MemoryManager} 的读写操作。
 *
 * 该类是早期记忆系统的内容载体，包含文本内容、标题、启用状态、标签和相关性分数。
 * 设计为不可变对象，所有字段通过构造器注入。新架构中更多使用 {@link MemoryEntry} 代替。
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
