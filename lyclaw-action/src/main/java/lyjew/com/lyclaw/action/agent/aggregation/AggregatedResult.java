package lyjew.com.lyclaw.action.agent.aggregation;

import lyjew.com.lyclaw.agent.CollaborationPattern;
import lyjew.com.lyclaw.react.subagent.SubagentResult;

import java.util.List;

public class AggregatedResult {

    private final boolean success;
    private final String summary;
    private final String detail;
    private final CollaborationPattern pattern;
    private final List<SubagentResult> individualResults;
    private final long totalDurationMs;
    private final int totalSubAgentCalls;
    private final int succeededCalls;
    private final int failedCalls;
    private final Double consensusConfidence;
    private final String error;

    private AggregatedResult(boolean success, String summary, String detail,
                              CollaborationPattern pattern, List<SubagentResult> individualResults,
                              long totalDurationMs, int totalSubAgentCalls, int succeededCalls,
                              int failedCalls, Double consensusConfidence, String error) {
        this.success = success;
        this.summary = summary;
        this.detail = detail;
        this.pattern = pattern;
        this.individualResults = individualResults;
        this.totalDurationMs = totalDurationMs;
        this.totalSubAgentCalls = totalSubAgentCalls;
        this.succeededCalls = succeededCalls;
        this.failedCalls = failedCalls;
        this.consensusConfidence = consensusConfidence;
        this.error = error;
    }

    public static AggregatedResult success(String summary, String detail,
                                            CollaborationPattern pattern,
                                            List<SubagentResult> results, long durationMs) {
        int total = results.size();
        int succeeded = (int) results.stream().filter(SubagentResult::isSuccess).count();
        return new AggregatedResult(true, summary, detail, pattern, results,
                durationMs, total, succeeded, total - succeeded, null, null);
    }

    public static AggregatedResult failure(String error, CollaborationPattern pattern,
                                            List<SubagentResult> results) {
        return new AggregatedResult(false, "失败: " + error, error, pattern, results,
                0, results.size(), 0, results.size(), null, error);
    }

    public static AggregatedResult consensus(String summary, List<SubagentResult> results,
                                              double confidence, long durationMs) {
        return new AggregatedResult(true, summary, summary, CollaborationPattern.VOTE,
                results, durationMs, results.size(), results.size(), 0, confidence, null);
    }

    public String formatAsMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 协作结果 (").append(pattern).append(")\n\n");

        if (!success) {
            sb.append("**状态**: FAILED\n");
            sb.append("**错误**: ").append(error).append("\n\n");
            return sb.toString();
        }

        sb.append("**状态**: SUCCESS\n");
        sb.append("**Agent 调用**: ").append(succeededCalls).append("/").append(totalSubAgentCalls).append(" 成功\n");
        sb.append("**耗时**: ").append(totalDurationMs).append("ms\n");
        if (consensusConfidence != null) {
            sb.append("**共识置信度**: ").append(String.format("%.2f", consensusConfidence)).append("\n");
        }
        sb.append("\n").append(summary).append("\n\n");

        if (detail != null && !detail.isEmpty()) {
            sb.append("### 详细结果\n").append(detail).append("\n");
        }

        if (individualResults != null && !individualResults.isEmpty()) {
            sb.append("### 各 Agent 输出\n");
            for (SubagentResult r : individualResults) {
                sb.append(r.formatAsObservation()).append("\n\n");
            }
        }

        return sb.toString();
    }

    public boolean isSuccess() { return success; }
    public String getSummary() { return summary; }
    public String getDetail() { return detail; }
    public CollaborationPattern getPattern() { return pattern; }
    public List<SubagentResult> getIndividualResults() { return individualResults; }
    public long getTotalDurationMs() { return totalDurationMs; }
    public int getTotalSubAgentCalls() { return totalSubAgentCalls; }
    public int getSucceededCalls() { return succeededCalls; }
    public int getFailedCalls() { return failedCalls; }
    public Double getConsensusConfidence() { return consensusConfidence; }
    public String getError() { return error; }
}
