package lyjew.com.lyclaw.reflect.model;

import lyjew.com.lyclaw.model.ChatRequest;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 贯穿整个拓扑执行过程的上下文对象，是所有原语之间传递数据的唯一载体（共享内存模式）。
 * 追加类字段用 CopyOnWriteArrayList 保证并发安全，覆盖类字段用 volatile。
 */
public class ReflectionContext {

    // ── 迭代状态 ──
    private volatile int iteration;
    private volatile String phaseName;
    private volatile String agentId;
    private volatile String sessionId;
    private volatile String userId;
    private volatile String projectId;

    // ── 外部管线 ChatRequest（用于回写 ReAct 工具调用消息到持久化层） ──
    private volatile ChatRequest chatRequest;

    // ── Actor 产物 ──
    private final List<String> outputs = new CopyOnWriteArrayList<>();
    private volatile String currentOutput;
    private final List<String> toolResults = new CopyOnWriteArrayList<>();

    // ── Evaluator 产物 ──
    private final List<Evaluation> evaluations = new CopyOnWriteArrayList<>();
    private volatile Evaluation lastEvaluation;
    private volatile List<Issue> currentIssues;

    // ── Reflector 产物 ──
    private final List<String> reflections = new CopyOnWriteArrayList<>();
    private volatile String currentReflection;

    // ── Synthesizer 产物 ──
    private volatile String finalOutput;

    // ── Memory 产物 ──
    private final List<MemoryRecord> activeMemories = new CopyOnWriteArrayList<>();
    private final List<MemoryRecord> retrievedSkills = new CopyOnWriteArrayList<>();
    private final List<MemoryRecord> retrievedExperiences = new CopyOnWriteArrayList<>();
    private final List<String> retrievedDocuments = new CopyOnWriteArrayList<>();

    // ── 检索与知识 ──
    private volatile String taskSummary;
    private final List<String> taskTags = new CopyOnWriteArrayList<>();
    private volatile boolean retrievalNeeded;

    // ── 计数与摘要 ──
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private volatile String systemPrompt;
    private volatile String userMessage;

    // ── 扩展属性 ──
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    // ── 构造器 ──
    public ReflectionContext() {}

    // ── 迭代状态 getters/setters ──
    public int getIteration() { return iteration; }
    public void setIteration(int v) { this.iteration = v; }
    public int incrementIteration() { return ++iteration; }
    public String getPhaseName() { return phaseName; }
    public void setPhaseName(String v) { this.phaseName = v; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String v) { this.agentId = v; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { this.sessionId = v; }
    public String getUserId() { return userId; }
    public void setUserId(String v) { this.userId = v; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String v) { this.projectId = v; }
    public ChatRequest getChatRequest() { return chatRequest; }
    public void setChatRequest(ChatRequest v) { this.chatRequest = v; }

    // ── Actor 产物 getters/setters ──
    public List<String> getOutputs() { return outputs; }
    public void addOutput(String v) { outputs.add(v); }
    public String getCurrentOutput() { return currentOutput; }
    public void setCurrentOutput(String v) { this.currentOutput = v; }
    public List<String> getToolResults() { return toolResults; }
    public void addToolResult(String v) { toolResults.add(v); }

    // ── Evaluator 产物 getters/setters ──
    public List<Evaluation> getEvaluations() { return evaluations; }
    public void addEvaluation(Evaluation v) { evaluations.add(v); }
    public Evaluation getLastEvaluation() { return lastEvaluation; }
    public void setLastEvaluation(Evaluation v) { this.lastEvaluation = v; }
    public List<Issue> getCurrentIssues() { return currentIssues; }
    public void setCurrentIssues(List<Issue> v) { this.currentIssues = v; }

    // ── Reflector 产物 getters/setters ──
    public List<String> getReflections() { return reflections; }
    public void addReflection(String v) { reflections.add(v); }
    public String getCurrentReflection() { return currentReflection; }
    public void setCurrentReflection(String v) { this.currentReflection = v; }

    // ── Synthesizer 产物 ──
    public String getFinalOutput() { return finalOutput; }
    public void setFinalOutput(String v) { this.finalOutput = v; }

    // ── Memory 产物 ──
    public List<MemoryRecord> getActiveMemories() { return activeMemories; }
    public List<MemoryRecord> getRetrievedSkills() { return retrievedSkills; }
    public List<MemoryRecord> getRetrievedExperiences() { return retrievedExperiences; }
    public List<String> getRetrievedDocuments() { return retrievedDocuments; }

    // ── 检索与知识 ──
    public String getTaskSummary() { return taskSummary; }
    public void setTaskSummary(String v) { this.taskSummary = v; }
    public List<String> getTaskTags() { return taskTags; }
    public boolean isRetrievalNeeded() { return retrievalNeeded; }
    public void setRetrievalNeeded(boolean v) { this.retrievalNeeded = v; }

    // ── 计数与摘要 ──
    public AtomicInteger getSuccessCount() { return successCount; }
    public AtomicInteger getFailCount() { return failCount; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String v) { this.systemPrompt = v; }
    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String v) { this.userMessage = v; }

    // ── 扩展属性 ──
    public Object getAttribute(String key) { return attributes.get(key); }
    public void setAttribute(String key, Object value) { attributes.put(key, value); }
    public Map<String, Object> getAttributes() { return attributes; }

    // ── 便利方法 ──
    public boolean isLastEvalSuccess() {
        return lastEvaluation != null && lastEvaluation.isSuccess();
    }
    public double getLastScore() {
        return lastEvaluation != null ? lastEvaluation.getScore() : 0.0;
    }
    public void addIssue(Issue issue) {
        if (currentIssues == null) currentIssues = new CopyOnWriteArrayList<>();
        currentIssues.add(issue);
    }

    @Override public String toString() {
        return "ReflectionContext{iteration=" + iteration + ", outputs=" + outputs.size()
                + ", evaluations=" + evaluations.size() + ", reflections=" + reflections.size()
                + ", lastScore=" + getLastScore() + "}";
    }
}
