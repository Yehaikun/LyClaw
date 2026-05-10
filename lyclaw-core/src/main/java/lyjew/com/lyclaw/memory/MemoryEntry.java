package lyjew.com.lyclaw.memory;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 统一记忆条目 —— 贯穿四层记忆系统的核心数据单元。
 *
 * <p>每条 MemoryEntry 属于一个 MemoryLayerType,
 * 通过 importance/accessCount/temporal 决定其生命周期和检索权重。</p>
 *
 * @since 2.0
 * @author LyClaw Team
 */
@Data
@Builder
public class MemoryEntry {

    /** 全局唯一ID (UUID) */
    private String entryId;

    /** 多租户支持 */
    private String userId;

    /** 所属会话ID */
    private String sessionId;

    /** 记忆层级 */
    private MemoryLayerType layer;

    /** 原始文本内容 */
    private String content;

    /** 压缩摘要 */
    private String summary;

    /** 向量嵌入 (维度由 EmbeddingModel 决定) */
    private float[] embedding;

    /** 记忆类别 */
    private MemoryCategory category;

    /** 重要性评分 [0.0, 1.0] */
    private double importance;

    /** 被访问次数 */
    private int accessCount;

    /** 时间属性 */
    private TemporalProps temporal;

    /** 标签 */
    private List<String> tags;

    /** 扩展元数据 */
    private Map<String, Object> metadata;

    public void incrementAccess() {
        this.accessCount++;
    }

    public double computeRelevanceScore(double alpha, double beta, double gamma, double delta) {
        return alpha * importance + beta * normalizeAccessCount() + gamma * temporal.computeDecay() + delta;
    }

    private double normalizeAccessCount() {
        return Math.min(1.0, accessCount / 100.0);
    }
}
