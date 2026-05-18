package lyjew.com.lyclaw.config;

/**
 * Task planning configuration properties under {@code lyclaw.plan}.
 */
public class PlanProperties {

    /** Default timeout for plan generation in milliseconds. */
    private long defaultTimeoutMs = 30_000;

    /** Timeout for simple single-node tasks in milliseconds. */
    private long simpleTimeoutMs = 10_000;

    /** Max task nodes in a plan before triggering validation warning. */
    private int maxNodes = 50;

    /** Total time budget for plan execution in milliseconds. */
    private long timeBudgetMs = 600_000;

    /** Confidence threshold for HybridPlanner: below this value LLM fallback is used. */
    private double hybridConfidenceThreshold = 0.5;

    /** Max ReAct cycles for ReActPlanner. */
    private int maxCycles = 5;

    public long getDefaultTimeoutMs() { return defaultTimeoutMs; }
    public void setDefaultTimeoutMs(long defaultTimeoutMs) { this.defaultTimeoutMs = defaultTimeoutMs; }
    public long getSimpleTimeoutMs() { return simpleTimeoutMs; }
    public void setSimpleTimeoutMs(long simpleTimeoutMs) { this.simpleTimeoutMs = simpleTimeoutMs; }
    public int getMaxNodes() { return maxNodes; }
    public void setMaxNodes(int maxNodes) { this.maxNodes = maxNodes; }
    public long getTimeBudgetMs() { return timeBudgetMs; }
    public void setTimeBudgetMs(long timeBudgetMs) { this.timeBudgetMs = timeBudgetMs; }
    public double getHybridConfidenceThreshold() { return hybridConfidenceThreshold; }
    public void setHybridConfidenceThreshold(double hybridConfidenceThreshold) { this.hybridConfidenceThreshold = hybridConfidenceThreshold; }
    public int getMaxCycles() { return maxCycles; }
    public void setMaxCycles(int maxCycles) { this.maxCycles = maxCycles; }
}
