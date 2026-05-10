package lyjew.com.lyclaw.memory.retriever;

import lyjew.com.lyclaw.memory.MemoryEntry;
import lyjew.com.lyclaw.memory.MemoryQuery;
import java.util.List;

/**
 * 记忆检索器 —— 混合检索策略的单一接口。
 *
 * <p>检索流程:
 * <ol>
 *   <li>query → EmbeddingModel.embed() 生成查询向量</li>
 *   <li>VectorStore.similarity() 粗排 top-100</li>
 *   <li>KeywordIndex.match() BM25 补充</li>
 *   <li>TimeDecay.apply() 时间衰减</li>
 *   <li>ImportanceWeight.apply() 重要性加权</li>
 *   <li>FusionRanker.merge() 精排 top-K</li>
 *   <li>结果去重</li>
 * </ol></p>
 *
 * @since 2.0
 */
public interface MemoryRetriever {

    List<MemoryEntry> retrieve(MemoryQuery query, List<MemoryEntry> candidatePool);

    String getRetrievalMethod();
}
