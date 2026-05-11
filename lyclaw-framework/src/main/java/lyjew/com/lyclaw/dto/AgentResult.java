package lyjew.com.lyclaw.dto;

/**
 * Agent 执行结果 DTO，封装单个 Agent 任务执行完成后的状态和输出。
 *
 * <p>不可变对象，包含 Agent 标识、执行状态（COMPLETED/FAILED/TIMEOUT/CANCELLED）、
 * 摘要、详细信息以及执行耗时。</p>
 */
public class AgentResult {

    /** Agent 唯一标识 */
    private final String agentId;
    /** 执行状态：COMPLETED / FAILED / TIMEOUT / CANCELLED */
    private final String status;
    /** 执行结果摘要 */
    private final String summary;
    /** 执行结果详细信息 */
    private final String detail;
    /** 执行耗时（毫秒） */
    private final long elapsedMs;

    public AgentResult(String agentId, String status, String summary,
                       String detail, long elapsedMs) {
        this.agentId = agentId;
        this.status = status;
        this.summary = summary;
        this.detail = detail;
        this.elapsedMs = elapsedMs;
    }

    public String getAgentId() { return agentId; }

    public String getStatus() { return status; }

    public String getSummary() { return summary; }

    public String getDetail() { return detail; }

    public long getElapsedMs() { return elapsedMs; }
}
