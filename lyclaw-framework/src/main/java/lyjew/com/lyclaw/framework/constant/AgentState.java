package lyjew.com.lyclaw.framework.constant;

/**
 * Agent 运行时状态枚举，描述 Agent 在当前任务中的执行状态。
 */
public enum AgentState {
    /** 空闲，Agent 等待新任务 */
    IDLE,
    /** 运行中，Agent 正在执行任务 */
    RUNNING,
    /** 等待中，Agent 等待外部事件或资源 */
    WAITING,
    /** 已完成，任务成功执行完毕 */
    COMPLETED,
    /** 已失败，任务执行过程中发生错误 */
    FAILED,
    /** 已取消，任务被主动取消 */
    CANCELLED
}
