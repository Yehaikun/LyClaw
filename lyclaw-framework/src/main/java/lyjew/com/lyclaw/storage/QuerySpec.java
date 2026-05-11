package lyjew.com.lyclaw.storage;

import java.util.HashMap;
import java.util.Map;

/**
 * 查询规约，封装一次多路检索的全部参数。
 *
 * <p>支持全文搜索关键词、向量相似搜索向量、元数据过滤
 * 以及多路融合权重配置。后端根据自身能力选择可用路径执行。</p>
 */
public class QuerySpec {

    /** 命名空间 */
    private String namespace;
    /** 全文搜索关键词 */
    private String fullTextKeyword;
    /** 向量搜索向量 */
    private float[] vector;
    /** 返回数量 */
    private int topK = 10;
    /** 元数据过滤条件 */
    private Map<String, Object> metadataFilter = new HashMap<>();
    /** 多路融合权重，如 {VECTOR: 0.6, BM25: 0.3, GRAPH: 0.1} */
    private Map<QueryPath, Double> weights = new HashMap<>();

    public QuerySpec() {}

    public static Builder builder() { return new Builder(); }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public String getFullTextKeyword() { return fullTextKeyword; }
    public void setFullTextKeyword(String fullTextKeyword) { this.fullTextKeyword = fullTextKeyword; }
    public float[] getVector() { return vector; }
    public void setVector(float[] vector) { this.vector = vector; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public Map<String, Object> getMetadataFilter() { return metadataFilter; }
    public void setMetadataFilter(Map<String, Object> metadataFilter) { this.metadataFilter = metadataFilter; }
    public Map<QueryPath, Double> getWeights() { return weights; }
    public void setWeights(Map<QueryPath, Double> weights) { this.weights = weights; }

    public static class Builder {
        private final QuerySpec spec = new QuerySpec();

        public Builder namespace(String ns) { spec.namespace = ns; return this; }
        public Builder fullTextKeyword(String kw) { spec.fullTextKeyword = kw; return this; }
        public Builder vector(float[] v) { spec.vector = v; return this; }
        public Builder topK(int k) { spec.topK = k; return this; }
        public Builder metadataFilter(Map<String, Object> filter) { spec.metadataFilter = filter; return this; }
        public Builder weights(Map<QueryPath, Double> w) { spec.weights = w; return this; }
        public Builder addWeight(QueryPath path, double w) { spec.weights.put(path, w); return this; }
        public QuerySpec build() { return spec; }
    }
}
