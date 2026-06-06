package lyjew.com.lyclaw.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 调用历史 —— 记录此 Agent 发起的子调用（工具调用 + Agent 委托）。
 *
 * <p>存储在 Agent Context 中（通过 {@link lyjew.com.lyclaw.session.VariableStore}），
 * 跨轮次持久化，支持 LLM 查询当前调用状态。</p>
 *
 * <p>关键能力：
 * <ul>
 *   <li>记录每次子调用的目标、任务、correlationId</li>
 *   <li>子完成时记录结果摘要</li>
 *   <li>格式化调用树供 LLM 上下文使用</li>
 *   <li>查询待处理的子调用（可用于进度检查）</li>
 * </ul>
 */
public class AgentCallHistory {

    private final String agentId;
    private final List<ChildCall> children = new ArrayList<>();
    private final Map<String, ChildCall> pendingByCorrelationId = new HashMap<>();
    private final Map<String, ChildCall> completedByCorrelationId = new HashMap<>();

    public AgentCallHistory(String agentId) {
        this.agentId = agentId;
    }

    /**
     * 记录发起的子调用。
     *
     * @param childAgentId 子 Agent ID
     * @param task         派发的任务描述
     * @param correlationId 关联 ID
     * @param ttlMs        超时毫秒
     */
    public void recordCall(String childAgentId, String task,
                           String correlationId, long ttlMs) {
        ChildCall call = new ChildCall(childAgentId, task, correlationId,
                System.currentTimeMillis(), ttlMs > 0 ? ttlMs : 300_000);
        children.add(call);
        pendingByCorrelationId.put(correlationId, call);
    }

    /**
     * 子 Agent 返回 RESPONSE 或 ERROR 时调用。
     *
     * @param correlationId 关联 ID
     * @param response      子 Agent 的响应消息
     */
    public void completeCall(String correlationId, AgentMessage response) {
        ChildCall call = pendingByCorrelationId.remove(correlationId);
        if (call != null) {
            call.complete(response);
            completedByCorrelationId.put(correlationId, call);
        }
    }

    /** 获取所有待处理的子调用 */
    public List<ChildCall> getPendingCalls() {
        return Collections.unmodifiableList(new ArrayList<>(pendingByCorrelationId.values()));
    }

    /** 获取所有已完成的子调用 */
    public List<ChildCall> getCompletedCalls() {
        return Collections.unmodifiableList(new ArrayList<>(completedByCorrelationId.values()));
    }

    /** 获取所有子调用 */
    public List<ChildCall> getAllCalls() {
        return Collections.unmodifiableList(children);
    }

    /** 是否有待处理的子调用 */
    public boolean hasPendingCalls() {
        return !pendingByCorrelationId.isEmpty();
    }

    /** 获取待处理调用数 */
    public int pendingCount() {
        return pendingByCorrelationId.size();
    }

    /**
     * 格式化调用树 —— 用于注入 LLM 上下文。
     *
     * 输出示例：
     * <pre>
     * 当前 Agent 的调用状态：
     * → code-reviewer: Review PR #42 [COMPLETED] (发现 3 个问题)
     * → github-tool: Fetch PR diff [COMPLETED]
     * → linter-tool: Run ESLint [IN_PROGRESS] (已运行 10s)
     * </pre>
     */
    public String formatCallTree() {
        if (children.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("当前 Agent 的调用状态：\n");
        for (ChildCall call : children) {
            sb.append("→ ").append(call.childAgentId)
              .append(": ").append(truncate(call.task, 60))
              .append(" [").append(call.status).append("]");
            if (call.completedAt != null) {
                long elapsed = call.completedAt - call.startedAt;
                sb.append(" (").append(elapsed).append("ms)");
            } else {
                long elapsed = System.currentTimeMillis() - call.startedAt;
                sb.append(" (已运行 ").append(elapsed / 1000).append("s)");
            }
            if (call.resultPreview != null && !call.resultPreview.isEmpty()) {
                sb.append(" 结果: ").append(call.resultPreview);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 通过 correlationId 获取子调用 */
    public ChildCall getCall(String correlationId) {
        ChildCall pending = pendingByCorrelationId.get(correlationId);
        if (pending != null) return pending;
        return completedByCorrelationId.get(correlationId);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /**
     * 单个子调用记录。
     */
    public static class ChildCall {
        private final String childAgentId;
        private final String task;
        private final String correlationId;
        private final long startedAt;
        private final long ttlMs;
        private Long completedAt;
        private String status;
        private String resultPreview;
        private String resultPayload;

        ChildCall(String childAgentId, String task, String correlationId,
                  long startedAt, long ttlMs) {
            this.childAgentId = childAgentId;
            this.task = task;
            this.correlationId = correlationId;
            this.startedAt = startedAt;
            this.ttlMs = ttlMs;
            this.status = "RUNNING";
        }

        void complete(AgentMessage response) {
            this.completedAt = System.currentTimeMillis();
            this.status = response.getType() == MessageType.ERROR ? "FAILED" : "COMPLETED";
            this.resultPayload = response.getPayload();
            this.resultPreview = truncate(response.getPayload(), 200);
        }

        public boolean isCompleted() { return completedAt != null; }
        public boolean isTimedOut() {
            return !isCompleted() && (System.currentTimeMillis() - startedAt) > ttlMs;
        }
        public long elapsed() { return (completedAt != null ? completedAt : System.currentTimeMillis()) - startedAt; }

        public String getChildAgentId() { return childAgentId; }
        public String getTask() { return task; }
        public String getCorrelationId() { return correlationId; }
        public long getStartedAt() { return startedAt; }
        public Long getCompletedAt() { return completedAt; }
        public String getStatus() { return status; }
        public String getResultPreview() { return resultPreview; }
        public String getResultPayload() { return resultPayload; }
    }
}
