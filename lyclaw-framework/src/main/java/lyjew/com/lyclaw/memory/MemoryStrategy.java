package lyjew.com.lyclaw.memory;

import lyjew.com.lyclaw.context.ChatContext;

/**
 * 记忆格式化与注入策略接口，决定记忆如何呈现给 LLM 上下文。
 *
 * 不同场景（摘要、问答、编码等）可能需要不同的格式化和筛选逻辑。
 * 实现类负责将 {@link MemoryContent} 转换为适合插入提示词的字符串格式，
 * 并根据当前对话上下文判断某条记忆是否应该被注入。
 */
public interface MemoryStrategy {

    /**
     * 将记忆内容格式化为适合注入 LLM 上下文的字符串。
     *
     * @param memory 待格式化的记忆内容
     * @return 格式化后的字符串，可直接拼接到系统提示或消息列表中
     */
    String formatForContext(MemoryContent memory);

    /**
     * 判断给定记忆是否应该包含在当前对话上下文中。
     *
     * 可根据内容的启用状态、标签与当前上下文的匹配度等做过滤。
     *
     * @param memory  待判断的记忆内容
     * @param context 当前对话上下文
     * @return true 表示应注入，false 表示跳过
     */
    boolean shouldIncludeInContext(MemoryContent memory, ChatContext context);

    /**
     * 获取该策略的优先级。
     *
     * 当有多个策略同时适用时，优先级高的策略优先使用。
     *
     * @return 优先级数值，越大越优先
     */
    int getPriority();
}
