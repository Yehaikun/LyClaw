package lyjew.com.lyclaw.reflect.topology;

import java.util.*;

/**
 * 拓扑执行结果摘要。
 */
public class ExecutionResult {
    private String finalOutput;
    private int totalIterations;
    private List<Double> scores = new ArrayList<>();
    private long totalDurationMs;
    private Map<String, Long> nodeDurations = new LinkedHashMap<>();

    public String getFinalOutput() { return finalOutput; }
    public void setFinalOutput(String v) { this.finalOutput = v; }
    public int getTotalIterations() { return totalIterations; }
    public void setTotalIterations(int v) { this.totalIterations = v; }
    public List<Double> getScores() { return scores; }
    public void setScores(List<Double> v) { this.scores = v; }
    public long getTotalDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(long v) { this.totalDurationMs = v; }
    public Map<String, Long> getNodeDurations() { return nodeDurations; }
    public void setNodeDurations(Map<String, Long> v) { this.nodeDurations = v; }
}
