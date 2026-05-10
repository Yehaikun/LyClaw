package lyjew.com.lyclaw.memory.consolidate;

import lyjew.com.lyclaw.memory.ConsolidationReport;
import lyjew.com.lyclaw.memory.MemoryConsolidationPolicy;

/**
 * 记忆巩固器 —— 将短期记忆提升为长期记忆。
 *
 * <p>识别重复模式 → 提升为长期记忆 → 合并相似记忆 → 遗忘不重要的。</p>
 * <p>默认通过定时任务触发 (每小时)。</p>
 *
 * @since 2.0
 */
public interface MemoryConsolidator {

    ConsolidationReport consolidate(String userId, String sessionId);
    ConsolidationReport consolidate(String userId, String sessionId, MemoryConsolidationPolicy policy);

    /** 是否支持 LLM 驱动的摘要生成 */
    boolean supportsLlmDrivenSummary();
}
