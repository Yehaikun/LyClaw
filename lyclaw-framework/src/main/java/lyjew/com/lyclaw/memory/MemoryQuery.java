package lyjew.com.lyclaw.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 记忆检索查询参数的封装对象。
 *
 * 支持文本查询和向量查询两种模式，融入了多路排序所需的权重系数（alpha/beta/gamma/delta）
 * 以及多维度过滤条件（层次、分类、标签、元数据）。使用 Builder 模式构造，
 * 各字段均有合理默认值。
 *
 * 使用 Lombok 自动生成 getter/setter/Builder 等样板方法。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryQuery {

    /** 查询文本，用于关键词匹配和向量嵌入 */
    private String queryText;
    /** 查询文本的向量嵌入，跳过嵌入步骤直接进行语义检索 */
    private float[] queryEmbedding;
    /** 返回的最大条目数，默认 20 */
    private int topK = 20;
    /** 重要性权重系数，默认 0.45 */
    private double alpha = 0.45;
    /** 访问频次权重系数，默认 0.20 */
    private double beta = 0.20;
    /** 时间衰减权重系数，默认 0.15 */
    private double gamma = 0.15;
    /** 常数偏置，默认 0.20，确保基线分数稳定 */
    private double delta = 0.20;
    /** 记忆层次过滤，为 null 表示不做层次过滤 */
    private List<MemoryLayerType> layerFilter;
    /** 记忆分类过滤，为 null 表示不做分类过滤 */
    private List<MemoryCategory> categoryFilter;
    /** 标签过滤，为 null 表示不做标签过滤 */
    private List<String> tagFilter;
    /** 元数据过滤条件，键值对精确匹配 */
    private Map<String, Object> metadataFilter;
}
