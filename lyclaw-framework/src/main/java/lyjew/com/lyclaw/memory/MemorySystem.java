package lyjew.com.lyclaw.memory;

import java.util.List;
import java.util.Optional;

/**
 * 记忆系统的顶层入口接口，定义了记忆全生命周期的核心操作。
 *
 * 该接口覆盖了从感知数据摄入、短期记忆存储、长期记忆固化、记忆检索、实体管理
 * 到记忆清理与统计的完整流程。所有具体实现都必须通过此接口提供一致的操作契约。
 * 设计上采用多层记忆模型（感官/短期/长期/实体），每一层有独立的存储和检索策略。
 */
public interface MemorySystem {

    /**
     * 摄入感知数据并创建感官层记忆条目。
     *
     * 将原始对话消息（用户/助手/工具调用等）封装为 {@link PerceptionData} 后输入系统，
     * 系统会根据时间戳和元数据生成一条感官层记忆，为后续提取和存储做准备。
     *
     * @param sessionId 会话标识，用于关联同一对话上下文的感知数据
     * @param data      包含角色、内容、时间戳等信息的感知数据
     * @return 新创建的感官层 {@link MemoryEntry}，含系统分配的唯一 entryId
     */
    MemoryEntry ingestPerception(String sessionId, PerceptionData data);

    /**
     * 将一条记忆条目显式存入短期记忆层。
     *
     * 通常配合 {@link MemoryExtractor} 使用——先从对话中提取关键信息生成条目，
     * 再通过此方法存入短期记忆层，使其可在当前会话内被检索。
     *
     * @param sessionId 当前会话标识
     * @param entry     待存储的记忆条目，需已设置层类型为 SHORT_TERM
     * @return 存储后的条目副本，可能被系统补充了额外字段（如 temporal 信息）
     */
    MemoryEntry storeShortTerm(String sessionId, MemoryEntry entry);

    /**
     * 将记忆条目固化为长期记忆。
     *
     * 长期记忆具有更高的持久性和更慢的衰减速率。通常由 {@link MemoryConsolidator}
     * 在固化流程中调用，将高重要度或高频访问的短期记忆提升为长期记忆。
     *
     * @param entry 待固化的记忆条目
     * @return 固化后的长期记忆条目，层类型已设为 LONG_TERM
     */
    MemoryEntry commitLongTerm(MemoryEntry entry);

    /**
     * 插入或更新一个实体记忆。
     *
     * 实体记忆用于存储人、物、地点等实体的结构化信息（属性、关系等），
     * 如果同一 entityType + entityId 已存在则执行覆盖更新。
     *
     * @param entity 实体记忆对象，含类型、ID、属性及关系列表
     */
    void upsertEntity(EntityMemory entity);

    /**
     * 执行多路记忆检索并返回排序后的结果。
     *
     * 检索过程融合向量相似度、关键词匹配、时效性衰减和实体关联等多路信号，
     * 最终由 {@link FusionRanker} 统一排序后返回 topK 条最相关记忆。
     *
     * @param query 检索查询对象，含查询文本/向量、topK、权重系数、过滤条件等
     * @return 检索结果，含排序后的条目列表、命中总数、耗时及检索方法
     */
    MemoryQueryResult retrieve(MemoryQuery query);

    /**
     * 获取指定会话的所有短期记忆。
     *
     * @param sessionId 会话标识
     * @return 该会话下的所有短期记忆条目（未过期且未固化的）
     */
    List<MemoryEntry> getShortTermMemories(String sessionId);

    /**
     * 根据上下文向量获取最相关的 topK 条长期记忆。
     *
     * 使用向量相似度检索，适用于将长期记忆注入对话上下文的场景。
     *
     * @param contextEmbedding 当前上下文的向量表示
     * @param topK             返回的最大条目数
     * @return 按相似度降序排列的长期记忆列表
     */
    List<MemoryEntry> getRelevantLongTerm(float[] contextEmbedding, int topK);

    /**
     * 根据实体类型和 ID 查询实体记忆。
     *
     * @param entityType 实体类型（如 "user"、"project"）
     * @param entityId   实体唯一标识
     * @return 对应的实体记忆，不存在时返回 {@link Optional#empty()}
     */
    Optional<EntityMemory> getEntity(String entityType, String entityId);

    /**
     * 对指定用户的记忆执行固化操作。
     *
     * 固化流程会评估短期记忆的重要性，将满足阈值条件的条目提升为长期记忆，
     * 同时合并相似条目以去重。具体行为由 {@link MemoryConsolidationPolicy} 控制。
     *
     * @param userId 用户标识
     * @param policy 固化策略配置（阈值、去重参数等）
     */
    void consolidate(String userId, MemoryConsolidationPolicy policy);

    /**
     * 驱逐所有已过期的感知层数据。
     *
     * 根据 {@link TemporalProps#isExpired()} 判断是否过期，批量清理感官层中
     * 已失效的数据以释放内存和存储空间。
     */
    void evictExpiredPerceptions();

    /**
     * 获取当前记忆系统的统计信息。
     *
     * @return 包含各层条目数量、token 总量、平均重要度等指标的 {@link MemoryStats}
     */
    MemoryStats getStats();
}
