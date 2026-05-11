package lyjew.com.lyclaw.storage;

/**
 * 记忆层级枚举，表示记忆从感知→短期→长期→实体的四层递进。
 *
 * <p>SENSORY 是最原始输入（短 TTL），SHORT_TERM 是工作记忆（中等 TTL），
 * LONG_TERM 是经过提炼的持久记忆，ENTITY 是结构化知识图谱实体。
 */
public enum MemoryLayer {
    /** 感知记忆——原始输入，TTL 分钟级 */
    SENSORY,
    /** 短期记忆——工作记忆，TTL 小时/天级 */
    SHORT_TERM,
    /** 长期记忆——持久化提炼后的记忆 */
    LONG_TERM,
    /** 实体记忆——结构化知识图谱 */
    ENTITY
}
