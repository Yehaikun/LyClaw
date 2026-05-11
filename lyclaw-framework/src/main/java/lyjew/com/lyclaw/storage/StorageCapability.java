package lyjew.com.lyclaw.storage;

/**
 * 存储能力枚举，声明存储后端支持的各项能力。
 *
 * <p>框架根据能力声明自动选择可用的查询路径：
 * 有 VECTOR_SEARCH 则启用向量检索，有 FULL_TEXT 则启用全文搜索，
 * 有 GRAPH 则启用图遍历，多个能力并存时走多路融合查询。</p>
 */
public enum StorageCapability {
    /** 基础键值存取——所有后端必须支持 */
    KEY_VALUE,
    /** 向量相似搜索——需要 @VectorStore 注解 */
    VECTOR_SEARCH,
    /** 全文搜索——需要 @FullTextStore 注解 */
    FULL_TEXT,
    /** 图遍历查询——需要 @GraphStore 注解 */
    GRAPH,
    /** 大文件二进制存储 */
    BLOB,
    /** 事务支持 */
    TRANSACTION,
    /** 流式读写 */
    STREAMING,
    /** 键生存时间 */
    TTL
}
