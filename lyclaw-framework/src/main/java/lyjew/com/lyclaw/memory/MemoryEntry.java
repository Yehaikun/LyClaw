package lyjew.com.lyclaw.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 记忆条目的核心数据模型，贯穿整个记忆系统的生命周期。
 *
 * 一条 MemoryEntry 可以存在于感官层、短期层或长期层，其生命周期由
 * {@link TemporalProps} 控制衰减与过期，重要性评分决定是否被固化。
 * 条目包含向量嵌入用于语义检索，分类标签和元数据用于过滤。
 *
 * 使用 Lombok 自动生成 getter/setter/构造器/Builder 等样板方法。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoryEntry {

    /** 系统分配的唯一标识 */
    private String entryId;
    /** 所属用户标识 */
    private String userId;
    /** 来源会话标识 */
    private String sessionId;
    /** 当前所在记忆层次（感官/短期/长期/实体） */
    private MemoryLayerType layer;
    /** 原始文本内容 */
    private String content;
    /** LLM 生成的摘要，用于快速浏览和注入上下文 */
    private String summary;
    /** 文本的向量嵌入表示，用于语义相似度检索 */
    private float[] embedding;
    /** 记忆分类（事实/偏好/事件等） */
    private MemoryCategory category;
    /** 重要性评分 [0, 1]，越高越可能被固化为长期记忆 */
    private double importance;
    /** 访问计数，用于热度加权 */
    private int accessCount;
    /** 时间属性（创建时间、过期时间、衰减因子等） */
    private TemporalProps temporal;
    /** 用户自定义或系统自动分配的标签 */
    private List<String> tags;
    /** 扩展元数据，键值对形式存储任意附加信息 */
    private Map<String, Object> metadata;

    /**
     * 递增访问计数。
     *
     * 每次被检索命中或注入上下文时调用，访问频次越高在融合排序中权重越大。
     */
    public void incrementAccess() {
        this.accessCount++;
    }

    /**
     * 计算综合相关性分数（加权融合）。
     *
     * 将重要性、访问热度、时间衰减和常数偏置线性加权，
     * 用于 {@link lyjew.com.lyclaw.memory.retriever.FusionRanker} 的多路融合排序。
     *
     * @param alpha 重要性权重，默认约 0.45
     * @param beta  访问频次权重，默认约 0.20
     * @param gamma 时间衰减权重，默认约 0.15
     * @param delta 常数偏置，确保基线分数，默认约 0.20
     * @return 综合相关性分数，值越高越相关
     */
    public double computeRelevanceScore(double alpha, double beta, double gamma, double delta) {
        return alpha * importance + beta * normalizeAccessCount() + gamma * temporal.computeDecay() + delta;
    }

    /**
     * 将访问次数归一化到 [0, 1] 区间。
     *
     * 使用线性归一化，以 100 次为饱和上限，超过 100 次视为满分。
     *
     * @return 归一化后的访问频率值
     */
    private double normalizeAccessCount() {
        return Math.min(1.0, accessCount / 100.0);
    }
}
