package lyjew.com.lyclaw.memory;

import java.util.List;

/**
 * 记忆内容 —— MemoryManager.read() 的返回值包装对象。
 *
 * <p>包含记忆正文（Markdown 格式）、标题、启用状态、标签列表和相关性评分。
 * ContextBuilder 在构建模型输入时，根据 MemoryStrategy 的决策来决定
 * 是否将 MemoryContent 注入到 System Prompt 中。</p>
 *
 * <p><b>设计动机</b>：长期记忆存储在 lyclaw-common 的 Memory 实体中
 * （单例 id="global"，含 content/title/enabled/tags 字段），
 * 但引擎层需要一个专用于上下文构建的只读视图，外加相关性评分信息。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>MemoryManager.read() 的返回值</li>
 *   <li>MemoryStrategy.formatForContext() 的输入参数</li>
 *   <li>ContextBuilder 构建消息列表时读取</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class MemoryContent {

    /** 记忆正文 —— Markdown 格式的文本内容 */
    private final String content;

    /** 人类可读的记忆标题，如 "用户偏好记忆"、"长期知识" */
    private final String title;

    /** 软开关。false 时 MemoryStrategy.shouldIncludeInContext() 返回 false */
    private final boolean enabled;

    /** 预留标签列表，用于记忆分类和检索 */
    private final List<String> tags;

    /**
     * 相关性评分 —— 用于上下文选择。
     * 0.0 表示不相关，1.0 表示完全相关。
     * 当有多条记忆时，只选择评分超过阈值的记忆注入上下文。
     */
    private final double relevanceScore;

    /**
     * 构造一个 MemoryContent 实例。
     *
     * @param content         记忆正文
     * @param title           记忆标题
     * @param enabled         是否启用
     * @param tags            标签列表
     * @param relevanceScore  相关性评分
     */
    public MemoryContent(String content, String title, boolean enabled,
                         List<String> tags, double relevanceScore) {
        this.content = content;
        this.title = title;
        this.enabled = enabled;
        this.tags = tags;
        this.relevanceScore = relevanceScore;
    }

    /** @return 记忆正文（Markdown 格式） */
    public String getContent() { return content; }

    /** @return 记忆标题 */
    public String getTitle() { return title; }

    /** @return 是否启用 */
    public boolean isEnabled() { return enabled; }

    /** @return 标签列表 */
    public List<String> getTags() { return tags; }

    /** @return 相关性评分 */
    public double getRelevanceScore() { return relevanceScore; }
}