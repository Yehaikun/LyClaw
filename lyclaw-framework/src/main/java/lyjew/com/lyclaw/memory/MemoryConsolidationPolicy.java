package lyjew.com.lyclaw.memory;

import lombok.Builder;
import lombok.Data;

/**
 * 记忆固化策略配置，控制短期记忆向长期记忆提升的规则与参数。
 *
 * 固化是记忆系统的核心流程之一——在对话结束后或达到阈值时，
 * 将高重要度的短期记忆提升为长期记忆，同时合并相似条目以去重。
 * 使用 Lombok 自动生成 getter/Builder 等样板方法。
 */
@Data
@Builder
public class MemoryConsolidationPolicy {
    /** 重要性阈值 [0, 1]，超过此值的短期记忆才被考虑固化，默认 0.7 */
    private double importanceThreshold = 0.7;
    /** 去重相似度阈值 [0, 1]，两条记忆的向量相似度超过此值视为重复，默认 0.85 */
    private double dedupThreshold = 0.85;
    /** 是否使用 LLM 驱动摘要生成（true）还是使用规则提取（false） */
    private boolean llmDrivenSummary = true;
    /** 单次固化批次的最大处理条目数，默认 100 */
    private int maxBatchSize = 100;
}
