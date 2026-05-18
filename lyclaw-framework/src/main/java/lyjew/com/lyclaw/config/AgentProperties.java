package lyjew.com.lyclaw.config;

/**
 * Agent configuration properties under {@code lyclaw.agent}.
 */
public class AgentProperties {

    /** Default interaction mode: react, cot, or hierarchical. */
    private String defaultMode = "react";

    /** Max tool-calling rounds per ReAct cycle. */
    private int maxToolRounds = 30;

    /** Timeout in seconds for tool approval UI interaction. */
    private int approvalTimeoutSeconds = 30;

    /** Timeout in seconds for approval store entries. */
    private int approvalStoreTimeoutSeconds = 60;

    /** Total timeout per agent invocation in milliseconds. */
    private long timeoutMs = 300_000;

    public String getDefaultMode() { return defaultMode; }
    public void setDefaultMode(String defaultMode) { this.defaultMode = defaultMode; }
    public int getMaxToolRounds() { return maxToolRounds; }
    public void setMaxToolRounds(int maxToolRounds) { this.maxToolRounds = maxToolRounds; }
    public int getApprovalTimeoutSeconds() { return approvalTimeoutSeconds; }
    public void setApprovalTimeoutSeconds(int approvalTimeoutSeconds) { this.approvalTimeoutSeconds = approvalTimeoutSeconds; }
    public int getApprovalStoreTimeoutSeconds() { return approvalStoreTimeoutSeconds; }
    public void setApprovalStoreTimeoutSeconds(int approvalStoreTimeoutSeconds) { this.approvalStoreTimeoutSeconds = approvalStoreTimeoutSeconds; }
    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
}
