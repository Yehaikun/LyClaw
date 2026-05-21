package lyjew.com.lyclaw.react.subagent;

import java.util.ArrayList;
import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * Result produced by a single subagent delegation call.
 *
 * <p>Captures the full outcome: success/failure status, the child LLM's final
 * text, tool-execution statistics, timing, and any nested child results (for
 * multi-level delegation chains). Static factory methods create well-typed
 * instances for the common terminal states — success, error, timeout, and
 * rejection — while {@link #formatAsObservation()} renders the result as a
 * Markdown observation string that the parent LLM can consume directly.</p>
 */
@Data
@Builder
public class SubagentResult {

    /** {@code true} if the subagent completed without error, timeout, or rejection. */
    private boolean success;

    /** Hierarchical session key for the child agent (e.g. {@code root/subagent/code-review/a1b2c3d4}). */
    private String sessionKey;

    /** Unique identifier of the child agent. */
    private String agentId;

    /** Final text output returned by the child LLM. */
    private String output;

    /** Human-readable error description; populated only when {@code success == false}. */
    private String error;

    /** Total wall-clock duration of the subagent invocation, in milliseconds. */
    private long durationMs;

    /** Number of tool calls that completed successfully. */
    private int successTools;

    /** Number of tool calls that failed. */
    private int failedTools;

    /** Results from nested delegations (children spawned by this subagent). */
    @Builder.Default
    private List<SubagentResult> childResults = new ArrayList<>();

    /** Optional reflection score produced by a reflection loop; {@code null} when reflection is disabled. */
    private Double reflectionScore;

    /** Total token consumption (input + output) for this subagent call. */
    private int totalTokens;

    // ================================================================
    // Static factory methods
    // ================================================================

    /**
     * Create a successful result.
     *
     * @param sessionKey   hierarchical session key of the child
     * @param agentId      child agent identifier
     * @param output       final LLM output text
     * @param durationMs   elapsed wall-clock time in milliseconds
     * @param successTools count of successful tool invocations
     * @param failedTools  count of failed tool invocations
     * @return a result with {@code success = true}
     */
    public static SubagentResult success(String sessionKey, String agentId, String output,
                                         long durationMs, int successTools, int failedTools) {
        return SubagentResult.builder()
                .success(true)
                .sessionKey(sessionKey)
                .agentId(agentId)
                .output(output)
                .durationMs(durationMs)
                .successTools(successTools)
                .failedTools(failedTools)
                .build();
    }

    /**
     * Create a generic error result.
     *
     * @param error human-readable error description
     * @return a result with {@code success = false}
     */
    public static SubagentResult error(String error) {
        return SubagentResult.builder()
                .success(false)
                .error(error)
                .build();
    }

    /**
     * Create a timeout result.
     *
     * @param agentId        child agent identifier
     * @param timeoutSeconds configured timeout in seconds
     * @return a result with {@code success = false}
     */
    public static SubagentResult timeout(String agentId, long timeoutSeconds) {
        return SubagentResult.builder()
                .success(false)
                .agentId(agentId)
                .error("Subagent timed out after " + timeoutSeconds + " seconds")
                .build();
    }

    /**
     * Create a rejection result (the subagent was denied by policy).
     *
     * @param agentId child agent identifier
     * @param reason  human-readable reason for rejection
     * @return a result with {@code success = false}
     */
    public static SubagentResult rejected(String agentId, String reason) {
        return SubagentResult.builder()
                .success(false)
                .agentId(agentId)
                .error("Subagent delegation rejected: " + reason)
                .build();
    }

    /**
     * Non-null proceed signal for use as a Mono sentinel value.
     * Not a real result; downstream flatMaps should ignore this and
     * map it to actual work. Only for internal use within spawnSubagent.
     */
    static SubagentResult proceed(String agentId) {
        return SubagentResult.builder()
                .success(true)
                .agentId(agentId)
                .output("__proceed__")
                .build();
    }

    // ================================================================
    // Formatting
    // ================================================================

    /**
     * Render this result as a Markdown-formatted tool observation string
     * suitable for direct injection into the parent LLM's context.
     *
     * <p>The output includes agent identity, status, timing, tool counts,
     * token usage, reflection score (when available), and a compact summary
     * of any nested child results.</p>
     *
     * @return formatted observation string
     */
    public String formatAsObservation() {
        StringBuilder sb = new StringBuilder();
        sb.append("### Subagent Result: `").append(agentId != null ? agentId : "unknown").append("`\n");

        if (success) {
            sb.append("**Status**: SUCCESS\n");
            sb.append("**Duration**: ").append(formatDuration(durationMs)).append("\n");
            sb.append("**Tools**: ").append(successTools).append(" succeeded");
            if (failedTools > 0) {
                sb.append(", ").append(failedTools).append(" failed");
            }
            sb.append("\n");
            sb.append("**Tokens**: ").append(totalTokens).append("\n");
            if (reflectionScore != null) {
                sb.append("**Reflection Score**: ").append(String.format("%.2f", reflectionScore)).append("\n");
            }
            if (sessionKey != null) {
                sb.append("**Session**: `").append(sessionKey).append("`\n");
            }
            sb.append("\n**Output**:\n");
            sb.append(output != null ? output : "(empty)\n");
        } else {
            sb.append("**Status**: FAILED\n");
            sb.append("**Error**: ").append(error != null ? error : "Unknown error").append("\n");
            if (agentId != null) {
                sb.append("**Agent**: `").append(agentId).append("`\n");
            }
            if (durationMs > 0) {
                sb.append("**Duration**: ").append(formatDuration(durationMs)).append("\n");
            }
        }

        if (childResults != null && !childResults.isEmpty()) {
            sb.append("\n**Nested Subagent Results** (").append(childResults.size()).append("):\n");
            for (int i = 0; i < childResults.size(); i++) {
                SubagentResult child = childResults.get(i);
                String status = child.success ? "OK" : "FAIL";
                sb.append("- [").append(i + 1).append("] `")
                        .append(child.agentId != null ? child.agentId : "?")
                        .append("` → ").append(status);
                if (!child.success && child.error != null) {
                    sb.append(": ").append(child.error);
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        }
        long seconds = ms / 1000;
        if (seconds < 60) {
            return String.format("%d.%ds", seconds, (int) ((ms % 1000) / 100));
        }
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format("%dm%ds", minutes, seconds);
    }
}
