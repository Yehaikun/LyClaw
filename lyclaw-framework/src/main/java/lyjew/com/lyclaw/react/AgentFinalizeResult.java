package lyjew.com.lyclaw.react;

/**
 * beforeAgentFinalize hook 的修订门控结果。
 * 对标 OpenClaw 的 AgentHarnessBeforeAgentFinalizeOutcome。
 */
public class AgentFinalizeResult {

    public enum Action { CONTINUE, REVISE, FINALIZE }

    private final Action action;
    private final String reason;
    private final String retryInstruction;
    private final String idempotencyKey;
    private final int maxAttempts;

    private AgentFinalizeResult(Action action, String reason, String retryInstruction,
                                String idempotencyKey, int maxAttempts) {
        this.action = action;
        this.reason = reason;
        this.retryInstruction = retryInstruction;
        this.idempotencyKey = idempotencyKey;
        this.maxAttempts = maxAttempts;
    }

    public static AgentFinalizeResult continue_() {
        return new AgentFinalizeResult(Action.CONTINUE, null, null, null, 0);
    }

    public static AgentFinalizeResult revise(String reason, String retryInstruction) {
        return new AgentFinalizeResult(Action.REVISE, reason, retryInstruction, null, 0);
    }

    public static AgentFinalizeResult revise(String reason, String retryInstruction,
                                              String idempotencyKey, int maxAttempts) {
        return new AgentFinalizeResult(Action.REVISE, reason, retryInstruction, idempotencyKey, maxAttempts);
    }

    public static AgentFinalizeResult finalize(String reason) {
        return new AgentFinalizeResult(Action.FINALIZE, reason, null, null, 0);
    }

    public Action getAction() { return action; }
    public String getReason() { return reason; }
    public String getRetryInstruction() { return retryInstruction; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public int getMaxAttempts() { return maxAttempts; }

    public boolean isContinue() { return action == Action.CONTINUE; }
    public boolean isRevise() { return action == Action.REVISE; }
    public boolean isFinalize() { return action == Action.FINALIZE; }
}
