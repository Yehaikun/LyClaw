package lyjew.com.lyclaw.storage;

/**
 * 存储层级枚举，对应框架的三层存储架构。
 *
 * <p>每层可独立选择后端：
 * SESSION 适合高频读写低延迟（Redis/InMemory），
 * ENTITY 需要 ACID 持久化（PostgreSQL/SQLite/File），
 * MEMORY 需要语义检索（PostgreSQL+pgvector/SQLite+vec/Milvus）。
 */
public enum StoreLayer {
    /** 会话层——单次会话或数分钟，TTL 自动清理 */
    SESSION,
    /** 实体层——长期持久、版本化管理 */
    ENTITY,
    /** 记忆层——跨 Session、多年保留，需语义检索 */
    MEMORY
}
