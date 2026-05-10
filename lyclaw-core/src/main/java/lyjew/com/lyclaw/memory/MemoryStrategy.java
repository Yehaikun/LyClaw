package lyjew.com.lyclaw.memory;

import lyjew.com.lyclaw.context.ChatContext;

/**
 * 记忆注入策略接口 —— 控制记忆如何注入到对话上下文中。
 *
 * <p>MemoryManager 负责记忆的存取，MemoryStrategy 负责决定"记忆如何呈现给模型"。
 * 不同的策略对记忆的格式化方式和注入条件有不同的处理。</p>
 *
 * <p><b>与 FormatStrategy 的区别</b>：
 * <ul>
 *   <li>MemoryStorage 的 MarkdownFormatStrategy 决定文件读写格式（.md / .json / .txt）</li>
 *   <li>MemoryStrategy 决定记忆如何注入上下文（标签包裹 / 摘要 / 嵌入向量相似度查询）</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see MemoryManager
 * @see ManualMemoryStrategy
 */
public interface MemoryStrategy {

    /**
     * 将记忆格式化为提示词片段。通常是包裹在 Memory 标签中。
     *
     * @param memory 记忆内容
     * @return 格式化后的字符串片段
     */
    String formatForContext(MemoryContent memory);

    /**
     * 判断是否应该在当前上下文中注入记忆。
     * 如果不应该注入，ContextBuilder 会跳过这条记忆。
     *
     * @param memory  记忆内容
     * @param context 当前对话上下文
     * @return true 表示注入上下文，false 表示跳过
     */
    boolean shouldIncludeInContext(MemoryContent memory, ChatContext context);

    /**
     * 获取策略优先级。当有多个策略时，优先级高的策略胜出。
     *
     * @return 优先级（值越大优先级越高）
     */
    int getPriority();
}
