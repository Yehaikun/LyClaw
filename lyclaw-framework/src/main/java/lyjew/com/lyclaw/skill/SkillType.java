package lyjew.com.lyclaw.skill;

/**
 * 技能类型枚举，标识技能的来源与组合方式。
 *
 * <p>用于在技能注册和调度时区分系统内置能力、用户自定义逻辑
 * 以及由多个子技能通过{@link SkillGraph}编排而成的复合技能。</p>
 */
public enum SkillType {
    /** 内置技能，框架自带的预定义技能 */
    BUILTIN,
    /** 用户自定义技能，由用户通过配置或代码注册 */
    USER_DEFINED,
    /** 组合技能，由多个子技能通过依赖图编排而成的复合技能 */
    COMPOSITE
}
