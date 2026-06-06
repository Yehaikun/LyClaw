package lyjew.com.lyclaw.model;

/**
 * 会话生命周期状态。
 *
 * <p>ACTIVE — 正常对话中，消息可追加、可送入 LLM。
 * ARCHIVED — 已归档，不可追加但可恢复查看和查询。
 * CLOSED — 已关闭，会话生命周期结束。</p>
 */
public enum SessionStatus {
    ACTIVE,
    ARCHIVED,
    CLOSED
}
