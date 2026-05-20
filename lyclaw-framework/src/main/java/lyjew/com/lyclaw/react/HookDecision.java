package lyjew.com.lyclaw.react;

import java.util.Collections;
import java.util.Map;

/**
 * Hook 门控决策 —— 对标 OpenClaw 的 HookDecision / InputGateDecision。
 */
public class HookDecision {

    public enum Outcome { PASS, BLOCK }

    private final Outcome outcome;
    private final String reason;
    private final String message;
    private final String category;
    private final Map<String, Object> metadata;

    private HookDecision(Outcome outcome, String reason, String message,
                         String category, Map<String, Object> metadata) {
        this.outcome = outcome;
        this.reason = reason;
        this.message = message;
        this.category = category;
        this.metadata = metadata != null ? Collections.unmodifiableMap(metadata) : Collections.emptyMap();
    }

    public static HookDecision pass() {
        return new HookDecision(Outcome.PASS, null, null, null, null);
    }

    public static HookDecision block(String reason) {
        return new HookDecision(Outcome.BLOCK, reason, null, null, null);
    }

    public static HookDecision block(String reason, String message, String category,
                                      Map<String, Object> metadata) {
        return new HookDecision(Outcome.BLOCK, reason, message, category, metadata);
    }

    public Outcome getOutcome() { return outcome; }
    public String getReason() { return reason; }
    public String getMessage() { return message; }
    public String getCategory() { return category; }
    public Map<String, Object> getMetadata() { return metadata; }

    public boolean isPass() { return outcome == Outcome.PASS; }
    public boolean isBlock() { return outcome == Outcome.BLOCK; }
}
