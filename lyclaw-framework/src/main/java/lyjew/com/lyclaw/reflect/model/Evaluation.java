package lyjew.com.lyclaw.reflect.model;

import java.util.*;

public class Evaluation {
    private double score;
    private String reasoning;
    private List<Issue> issues = new ArrayList<>();
    private Map<String, Double> dimensions = new LinkedHashMap<>();
    private boolean needsRetry;
    private boolean isSuccess;
    private boolean isConsistent;
    private List<Inconsistency> inconsistencies = new ArrayList<>();
    private double importanceScore;
    private String rawOutput;
    private String category;
    private int testCount;
    private int passCount;

    public double getScore() { return score; }
    public void setScore(double v) { this.score = v; }
    public String getReasoning() { return reasoning; }
    public void setReasoning(String v) { this.reasoning = v; }
    public List<Issue> getIssues() { return issues; }
    public void setIssues(List<Issue> v) { this.issues = v; }
    public Map<String, Double> getDimensions() { return dimensions; }
    public void setDimensions(Map<String, Double> v) { this.dimensions = v; }
    public boolean isNeedsRetry() { return needsRetry; }
    public void setNeedsRetry(boolean v) { this.needsRetry = v; }
    public boolean isSuccess() { return isSuccess; }
    public void setSuccess(boolean v) { this.isSuccess = v; }
    public boolean isConsistent() { return isConsistent; }
    public void setConsistent(boolean v) { this.isConsistent = v; }
    public List<Inconsistency> getInconsistencies() { return inconsistencies; }
    public void setInconsistencies(List<Inconsistency> v) { this.inconsistencies = v; }
    public double getImportanceScore() { return importanceScore; }
    public void setImportanceScore(double v) { this.importanceScore = v; }
    public String getRawOutput() { return rawOutput; }
    public void setRawOutput(String v) { this.rawOutput = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public int getTestCount() { return testCount; }
    public void setTestCount(int v) { this.testCount = v; }
    public int getPassCount() { return passCount; }
    public void setPassCount(int v) { this.passCount = v; }
    public String toString() { return "Evaluation{score=" + score + ", isSuccess=" + isSuccess + ", issues=" + issues + "}"; }
}
