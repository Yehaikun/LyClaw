package lyjew.com.lyclaw.constant;

/**
 * 记忆类型枚举，定义 Agent 记忆系统的不同存储层级。
 *
 * <p>从即时上下文感知到长期知识积累，逐层管理 Agent 的信息与经验：
 * PERCEPTION 当前交互即时上下文，SHORT_TERM 会话内短期历史，
 * LONG_TERM 跨会话持久化知识，ENTITY 与特定实体关联的结构化信息。</p>
 */
public enum MemoryType {
    /** 感知记忆，当前交互的即时上下文信息 */
    PERCEPTION,
    /** 短期记忆，会话范围内的历史交互信息 */
    SHORT_TERM,
    /** 长期记忆，跨会话持久化存储的知识与经验 */
    LONG_TERM,
    /** 实体记忆，与特定实体（如用户、文档）关联的结构化信息 */
    ENTITY
}
