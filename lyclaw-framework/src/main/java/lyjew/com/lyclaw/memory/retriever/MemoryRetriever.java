package lyjew.com.lyclaw.memory.retriever;

import lyjew.com.lyclaw.memory.MemoryEntry;
import lyjew.com.lyclaw.memory.MemoryQuery;

import java.util.List;

/**
 * 记忆检索器接口，定义从候选池中检索记忆的单一检索路径。
 *
 * 不同的实现对应不同的检索方法——向量相似度检索、关键词匹配检索、
 * 混合检索等。每条路径独立返回结果，最终由 {@link FusionRanker} 融合排序。
 */
public interface MemoryRetriever {

    /**
     * 从候选池中检索与查询相关的记忆。
     *
     * 根据查询参数（文本、向量、过滤条件）从候选池中筛选出相关条目。
     * 返回结果可能不排序或仅做粗排，最终排序由 FusionRanker 完成。
     *
     * @param query         检索查询参数
     * @param candidatePool 候选记忆池（整个检索空间）
     * @return 相关记忆条目列表
     */
    List<MemoryEntry> retrieve(MemoryQuery query, List<MemoryEntry> candidatePool);

    /**
     * 获取该检索器使用的检索方法标识。
     *
     * 用于在 {@link lyjew.com.lyclaw.memory.MemoryQueryResult} 中记录
     * 实际使用的检索方法，便于性能分析和诊断。
     *
     * @return 检索方法名称（如 "vector"、"keyword"、"hybrid"）
     */
    String getRetrievalMethod();
}
