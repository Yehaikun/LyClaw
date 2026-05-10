package lyjew.com.lyclaw.dto;

public class AgentResult {

    private final String agentId;
    private final String status;
    private final String summary;
    private final String detail;
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
