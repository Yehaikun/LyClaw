package lyjew.com.lyclaw.memory;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MemoryConsolidationPolicy {

    /** 重要性阈值: 高于此值的短期记忆提升为长期记忆 */
    private double importanceThreshold = 0.7;

    /** 语义去重阈值: 相似度高于此值的记忆合并 */
    private double dedupThreshold = 0.85;

    /** 是否启用 LLM 驱动的摘要生成 */
    private boolean llmDrivenSummary = true;

    /** 每次巩固的最大记忆条数 */
    private int maxBatchSize = 100;
}
