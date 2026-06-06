package lyjew.com.lyclaw.mesh;

/**
 * Agent 错误恢复策略 —— 定义 Agent 失败时的处理方式。
 *
 * <p>用于 {@link AgentSpec#getSupervisionStrategy()}，
 * 配合 AgentMesh 的 EventBus 实现 Supervision Tree。</p>
 */
public enum SupervisionStrategy {
    /** 自动重启 Agent（默认） */
    RESTART,
    /** 不重启，上报错误到上级 Supervisor */
    ESCALATE,
    /** 忽略错误，仅记录日志 */
    IGNORE,
    /** 停止 Agent */
    STOP
}
