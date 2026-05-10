package lyjew.com.lyclaw.memory.extractor;

import lyjew.com.lyclaw.memory.MemoryEntry;
import java.util.List;

/**
 * 记忆提取器 —— 自动从对话中提取值得记住的信息。
 *
 * <p>触发时机:
 * <ul>
 *   <li>每条 assistant 回复生成后实时提取</li>
 *   <li>每 N 轮对话后批量提取</li>
 *   <li>会话结束时一次性提取</li>
 * </ul></p>
 *
 * <p>不依赖用户主动说"记住XX"，而是通过 LLM 自动识别关键信息。</p>
 *
 * @since 2.0
 */
public interface MemoryExtractor {

    /**
     * 从对话中提取记忆条目。
     *
     * @param conversation 对话文本 (最近N轮)
     * @param existingMemories 已有记忆 (用于去重和补全)
     * @return 提取出的新记忆条目列表
     */
    List<MemoryEntry> extract(String conversation, List<MemoryEntry> existingMemories);

    /** 是否支持实时提取 */
    boolean supportsRealtime();

    /** 提取器名称 */
    String getExtractorName();
}
