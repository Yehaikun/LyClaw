package lyjew.com.lyclaw.reflect.model;

import java.util.*;

public class MemoryRecord {
    private String recordId;
    private MemoryType type;
    private String userId;
    private String projectId;
    private String sessionId;
    private String agentId;
    private String taskType;
    private String summary;
    private String content;
    private List<Double> embedding;
    private double importanceScore;
    private int accessCount;
    private int successReferCount;
    private long createdAt;
    private long updatedAt;
    private Long expiresAt;

    public MemoryRecord() {}
    public String getRecordId() { return recordId; }
    public void setRecordId(String v) { this.recordId = v; }
    public MemoryType getType() { return type; }
    public void setType(MemoryType v) { this.type = v; }
    public String getUserId() { return userId; }
    public void setUserId(String v) { this.userId = v; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String v) { this.projectId = v; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { this.sessionId = v; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String v) { this.agentId = v; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String v) { this.taskType = v; }
    public String getSummary() { return summary; }
    public void setSummary(String v) { this.summary = v; }
    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }
    public List<Double> getEmbedding() { return embedding; }
    public void setEmbedding(List<Double> v) { this.embedding = v; }
    public double getImportanceScore() { return importanceScore; }
    public void setImportanceScore(double v) { this.importanceScore = v; }
    public int getAccessCount() { return accessCount; }
    public void setAccessCount(int v) { this.accessCount = v; }
    public int getSuccessReferCount() { return successReferCount; }
    public void setSuccessReferCount(int v) { this.successReferCount = v; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long v) { this.createdAt = v; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long v) { this.updatedAt = v; }
    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long v) { this.expiresAt = v; }
}
