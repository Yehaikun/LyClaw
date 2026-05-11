package lyjew.com.lyclaw.memory.extractor;

import lyjew.com.lyclaw.memory.MemoryEntry;

import java.util.List;

/**
 * 记忆提取器接口，从对话文本中提取结构化记忆条目。
 *
 * 这是记忆系统的信息抽取层，负责分析原始对话内容并生成一系列
 * {@link MemoryEntry} 对象。提取时可以结合已有的记忆进行去重和关联。
 * 不同实现可能使用规则匹配、LLM 调用或混合方式。
 */
public interface MemoryExtractor {

    /**
     * 从对话文本中提取记忆条目。
     *
     * 分析对话内容，识别其中的事实、偏好、事件等关键信息，
     * 并结合已有记忆列表避免重复提取相同内容。
     *
     * @param conversation      完整的对话文本（可能是单轮或多轮拼接）
     * @param existingMemories  已有的记忆列表，用于去重和上下文参考
     * @return 新提取的记忆条目列表，可能为空
     */
    List<MemoryEntry> extract(String conversation, List<MemoryEntry> existingMemories);

    /**
     * 判断该提取器是否支持实时（在线）提取。
     *
     * 实时提取器可以在每条消息到达时即时处理，而非实时提取器则
     * 需要累积一定量的对话后才能批量提取。
     *
     * @return true 表示支持实时提取
     */
    boolean supportsRealtime();

    /**
     * 获取提取器的名称标识。
     *
     * 用于日志记录、统计和运行时选择提取器。
     *
     * @return 提取器名称（如 "LLMExtractor"、"RuleExtractor"）
     */
    String getExtractorName();
}
