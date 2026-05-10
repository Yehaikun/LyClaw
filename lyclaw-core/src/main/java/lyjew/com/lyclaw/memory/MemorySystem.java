package lyjew.com.lyclaw.memory;

import java.util.List;
import java.util.Optional;

/**
 * 四层记忆系统顶层接口 —— 替代原有的 {@link MemoryManager}。
 *
 * <p>四层架构:
 * <ol>
 *   <li>PerceptionMemory — 感知记忆(单次对话, RingBuffer)</li>
 *   <li>ShortTermMemory — 短期记忆(单次会话, 向量存储+结构化摘要)</li>
 *   <li>LongTermMemory  — 长期记忆(跨会话, DB+向量存储, 时间衰减)</li>
 *   <li>EntityMemory    — 实体记忆(跨会话, 图/关系存储)</li>
 * </ol></p>
 *
 * <p>写入路径: ingestPerception → storeShortTerm → commitLongTerm → upsertEntity</p>
 * <p>读取路径: retrieve(query) 混合检索四层, 按权重融合排序</p>
 *
 * @since 2.0
 * @author LyClaw Team
 * @see MemoryEntry
 * @see MemoryManager
 */
public interface MemorySystem {

    // ========== 写入 ==========

    MemoryEntry ingestPerception(String sessionId, PerceptionData data);

    MemoryEntry storeShortTerm(String sessionId, MemoryEntry entry);

    MemoryEntry commitLongTerm(MemoryEntry entry);

    void upsertEntity(EntityMemory entity);

    // ========== 读取 ==========

    MemoryQueryResult retrieve(MemoryQuery query);

    List<MemoryEntry> getShortTermMemories(String sessionId);

    List<MemoryEntry> getRelevantLongTerm(float[] contextEmbedding, int topK);

    Optional<EntityMemory> getEntity(String entityType, String entityId);

    // ========== 生命周期 ==========

    void consolidate(String userId, MemoryConsolidationPolicy policy);

    void evictExpiredPerceptions();

    MemoryStats getStats();
}
