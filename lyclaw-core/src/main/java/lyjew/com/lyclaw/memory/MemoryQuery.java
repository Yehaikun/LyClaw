package lyjew.com.lyclaw.memory;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class MemoryQuery {

    /** 自然语言查询字符串 */
    private String queryText;

    /** 查询向量嵌入 (与 queryText 二选一，优先使用) */
    private float[] queryEmbedding;

    /** 返回最大条数 */
    private int topK = 20;

    /** 向量相似度权重 */
    private double alpha = 0.45;

    /** 关键词BM25权重 */
    private double beta = 0.20;

    /** 时间衰减权重 */
    private double gamma = 0.15;

    /** 重要性权重 */
    private double delta = 0.20;

    /** 过滤条件: 记忆层级 */
    private List<MemoryLayerType> layerFilter;

    /** 过滤条件: 类别 */
    private List<MemoryCategory> categoryFilter;

    /** 过滤条件: 标签 */
    private List<String> tagFilter;

    /** 过滤条件: 元数据键值对 */
    private Map<String, Object> metadataFilter;
}
