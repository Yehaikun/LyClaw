package lyjew.com.lyclaw.memory;

import java.util.List;
import java.util.Optional;

/**
 * 记忆系统的顶层入口接口，定义了记忆全生命周期的核心操作。
 *
 * 该接口覆盖了从感知数据摄入、短期记忆存储、长期记忆固化、记忆检索
 * 到记忆清理与统计的完整流程。
 */
public interface MemorySystem {

    /**
     * 摄入感知数据并创建记忆条目。
     *
     * @param sessionId 会话标识
     * @param data      包含角色、内容、时间戳等信息的感知数据
     * @return 新创建的 {@link MemoryEntry}
     */
    MemoryEntry ingestPerception(String sessionId, PerceptionData data);

    /**
     * 将一条记忆条目显式存入短期记忆层。
     *
     * @param sessionId 当前会话标识
     * @param entry     待存储的记忆条目
     * @return 存储后的条目副本
     */
    MemoryEntry storeShortTerm(String sessionId, MemoryEntry entry);

    /**
     * 将记忆条目固化为长期记忆。
     *
     * @param entry 待固化的记忆条目
     * @return 固化后的长期记忆条目
     */
    MemoryEntry commitLongTerm(MemoryEntry entry);

    /**
     * 执行记忆检索并返回排序后的结果。
     *
     * @param query 检索查询对象，含查询文本、topK、过滤条件等
     * @return 检索结果
     */
    MemoryQueryResult retrieve(MemoryQuery query);

    /**
     * 获取指定会话的所有短期记忆。
     *
     * @param sessionId 会话标识
     * @return 该会话下的所有短期记忆条目
     */
    List<MemoryEntry> getShortTermMemories(String sessionId);

    /**
     * 根据上下文向量获取最相关的 topK 条长期记忆。
     *
     * @param contextEmbedding 当前上下文的向量表示
     * @param topK             返回的最大条目数
     * @return 按相似度降序排列的长期记忆列表
     */
    List<MemoryEntry> getRelevantLongTerm(float[] contextEmbedding, int topK);

    /**
     * 对指定用户的记忆执行固化操作。
     *
     * @param userId 用户标识
     * @param policy 固化策略配置
     */
    void consolidate(String userId, MemoryConsolidationPolicy policy);

    /**
     * 驱逐所有已过期的感知层数据。
     */
    void evictExpiredPerceptions();

    /**
     * 获取当前记忆系统的统计信息。
     *
     * @return 包含各层条目数量、token 总量等指标的 {@link MemoryStats}
     */
    MemoryStats getStats();

    /**
     * 获取所有长期记忆条目的不可变快照。
     *
     * @return 长期记忆条目列表
     */
    List<MemoryEntry> getLongTermEntries();

    /**
     * 从长期记忆层移除指定条目。
     *
     * @param entryId 待移除的条目 ID
     */
    void removeLongTermEntry(String entryId);
}
