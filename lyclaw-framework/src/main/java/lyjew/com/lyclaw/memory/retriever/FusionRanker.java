package lyjew.com.lyclaw.memory.retriever;

import lyjew.com.lyclaw.memory.MemoryEntry;
import lyjew.com.lyclaw.memory.MemoryQuery;

import java.util.List;

/**
 * 融合排序器接口，将多路检索结果融合并重排序。
 *
 * 检索可能来自多条路径（向量相似度、关键词匹配、实体关联等），
 * 融合排序器负责对候选集进行统一的加权打分和排序，
 * 综合重要性、访问频次、时间衰减等多个维度。
 */
public interface FusionRanker {

    /**
     * 对候选记忆列表进行融合排序。
     *
     * 对每条候选记忆调用 {@link #computeFusionScore} 计算综合分数，
     * 然后按分数降序排列并截断到 topK。
     *
     * @param candidates 待排序的候选记忆列表（多路检索的并集）
     * @param query      检索查询参数，含权重系数和过滤条件
     * @return 按融合分数降序排列的记忆列表，长度不超过 query.topK
     */
    List<MemoryEntry> rank(List<MemoryEntry> candidates, MemoryQuery query);

    /**
     * 计算单条记忆的融合评分。
     *
     * 综合重要性（alpha）、访问频次（beta）、时间衰减（gamma）和常数偏置（delta）
     * 四个维度加权求和，公式为：
     * score = alpha * importance + beta * normalizedAccess + gamma * temporalDecay + delta
     *
     * @param entry 待评分的记忆条目
     * @param query 检索查询参数，提供权重系数
     * @return 融合后的综合评分
     */
    double computeFusionScore(MemoryEntry entry, MemoryQuery query);
}
