package lyjew.com.lyclaw.memory.retriever;

import lyjew.com.lyclaw.memory.MemoryEntry;
import lyjew.com.lyclaw.memory.MemoryQuery;
import java.util.List;

/**
 * 融合排序器 —— 混合多种检索信号进行精排。
 *
 * <p>融合公式: finalScore = α×vectorScore + β×keywordScore + γ×temporalDecay + δ×importance</p>
 *
 * @since 2.0
 */
public interface FusionRanker {

    List<MemoryEntry> rank(List<MemoryEntry> candidates, MemoryQuery query);

    double computeFusionScore(MemoryEntry entry, MemoryQuery query);
}
