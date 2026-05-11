package lyjew.com.lyclaw.storage;

/**
 * 多路融合查询中的检索路径枚举。
 *
 * <p>当存储后端同时支持多种检索方式时，通过权重配比
 * 决定最终的融合排序。各路径权重之和通常为 1.0。</p>
 */
public enum QueryPath {
    /** 向量语义相似度检索 */
    VECTOR,
    /** BM25 关键词稀疏检索 */
    BM25,
    /** 图关系遍历检索 */
    GRAPH,
    /** 精确关键词匹配 */
    KEYWORD,
    /** 时间衰减加权检索 */
    TEMPORAL
}
