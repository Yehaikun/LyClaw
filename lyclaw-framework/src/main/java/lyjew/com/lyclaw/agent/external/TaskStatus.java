package lyjew.com.lyclaw.agent.external;

/**
 * 远程任务状态枚举，描述外部代理上任务的执行阶段。
 *
 * 该枚举用于 ExternalAgentAdapter.queryTaskStatus 的返回值，
 * 反映远程任务在当前时刻所处的阶段：PENDING（排队等待）、
 * RUNNING（正在执行）、COMPLETED（成功完成）、FAILED（执行失败）、
 * CANCELLED（已被取消）。调用方通过轮询状态来异步获取任务进度，
 * 当状态变为 COMPLETED、FAILED 或 CANCELLED 时表示任务已终结。
 */
public enum TaskStatus {
    /** 排队等待：任务已提交但尚未开始执行 */
    PENDING,
    /** 正在执行：远程代理正在处理任务 */
    RUNNING,
    /** 已完成：任务成功执行完毕 */
    COMPLETED,
    /** 失败：任务执行过程中发生错误 */
    FAILED,
    /** 已取消：任务被外部取消 */
    CANCELLED
}
