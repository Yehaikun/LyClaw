package lyjew.com.lyclaw.memory.consolidate;

import lyjew.com.lyclaw.memory.ConsolidationReport;
import lyjew.com.lyclaw.memory.MemoryConsolidationPolicy;

/**
 * 记忆固化器接口，负责将高重要度的短期记忆提升为长期记忆。
 *
 * 固化流程是记忆系统的关键环节——它评估短期记忆的重要性、合并相似条目、
 * 生成摘要并将符合条件的条目迁移到长期存储层。默认使用系统策略，
 * 也可通过参数传入自定义 {@link MemoryConsolidationPolicy}。
 */
public interface MemoryConsolidator {

    /**
     * 使用默认策略对指定用户和会话的记忆执行固化。
     *
     * @param userId    用户标识
     * @param sessionId 会话标识
     * @return 固化操作报告，包含提升、合并、清理的条目统计
     */
    ConsolidationReport consolidate(String userId, String sessionId);

    /**
     * 使用自定义策略对指定用户和会话的记忆执行固化。
     *
     * @param userId    用户标识
     * @param sessionId 会话标识
     * @param policy    自定义固化策略，覆盖默认的阈值和去重参数
     * @return 固化操作报告
     */
    ConsolidationReport consolidate(String userId, String sessionId, MemoryConsolidationPolicy policy);

    /**
     * 判断当前固化器是否支持 LLM 驱动的摘要生成。
     *
     * LLM 驱动模式会调用大模型对记忆内容进行摘要，
     * 非 LLM 模式则使用规则提取。在资源受限环境下可能无法使用 LLM 模式。
     *
     * @return true 表示支持 LLM 摘要，false 表示仅支持规则摘要
     */
    boolean supportsLlmDrivenSummary();
}
