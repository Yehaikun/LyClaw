package lyjew.com.lyclaw.agent;

/**
 * Agent 状态枚举 —— Agent 的完整生命周期状态。
 *
 * <p>状态流转：
 * <pre>
 * IDLE -> RUNNING -> WAITING -> RUNNING -> COMPLETED
 *                    \u21b3                    \u21b3 FAILED
 *                 CANCELLED
 * </pre>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public enum AgentState {
    IDLE,
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    CANCELLED
}