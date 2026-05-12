package lyjew.com.lyclaw.constant;

/**
 * 技能执行状态枚举，描述技能在其生命周期中的各阶段状态。
 */
public enum SkillExecutionStatus {
    /** 等待执行 */
    PENDING,
    /** 正在执行 */
    RUNNING,
    /** 已暂停 */
    PAUSED,
    /** 执行完成 */
    COMPLETED,
    /** 执行失败 */
    FAILED,
    /** 已取消 */
    CANCELLED
}
