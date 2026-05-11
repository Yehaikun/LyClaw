package lyjew.com.lyclaw.memory;

/**
 * 记忆层次枚举，定义记忆在多层架构中所处的层级。
 *
 * 设计灵感源自人类记忆模型：
 * SENSORY 为瞬时感知输入，SHORT_TERM 为短期内可访问的工作记忆，
 * LONG_TERM 为持久化的长期记忆，ENTITY 为结构化的实体知识。
 * 每层有不同的衰减速率和检索优先级。
 */
public enum MemoryLayerType {
    /** 感官层——原始对话消息，生命周期最短，快速衰减 */
    SENSORY,
    /** 短期层——从对话中提取的关键信息，会话级别可见 */
    SHORT_TERM,
    /** 长期层——经固化后的持久记忆，跨会话保留 */
    LONG_TERM,
    /** 实体层——结构化的人/物/地信息，无限期保留 */
    ENTITY
}
