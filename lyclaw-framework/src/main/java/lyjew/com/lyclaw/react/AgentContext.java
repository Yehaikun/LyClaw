package lyjew.com.lyclaw.react;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.task.TaskNode;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.tracing.TraceContext;

/**
 * Agent 调用上下文，聚合一次 Agent 调用的全部运行时数据和状态。
 *
 * <p>聚合了原 ChatContext 的全部字段，作为 Stage 管线、AgentHook 链、ReAct 循环之间的统一数据总线。</p>
 *
 * <h3>生命周期</h3>
 * <ul>
 *   <li>TRANSIENT — 单次调用，调用结束即丢弃</li>
 *   <li>SESSION — 绑定会话，跨多次调用共享</li>
 *   <li>PERSISTENT — 持久化，重启后仍可恢复</li>
 * </ul>
 */
public class AgentContext {

    public enum Lifecycle { TRANSIENT, SESSION, PERSISTENT }

    // ========== Agent 标识 ==========
    private final String sessionId;
    private String userMessage;
    private String systemPrompt;
    private ChatRequest chatRequest;
    private final ToolRegistry toolRegistry;
    private final Method method;
    private final Object[] args;
    private SandboxLevel sandboxLevel;
    private Lifecycle lifecycle = Lifecycle.TRANSIENT;

    // ========== 追踪 ==========
    private final TraceContext tracing;

    // ========== 流水线状态 ==========
    private final List<String> toolResults = new CopyOnWriteArrayList<>();
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private final List<TaskNode> nodes = new CopyOnWriteArrayList<>();
    private final AtomicReference<Double> reflectScoreRef = new AtomicReference<>(0.0);
    private final AtomicBoolean pipelineOk = new AtomicBoolean(false);
    private final AtomicLong respondStartMs = new AtomicLong();
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private final AtomicReference<String> currentStage = new AtomicReference<>("init");

    // ========== 扩展属性 ==========
    private final Map<String, Object> attributes = new HashMap<>();

    // ========== Phase 1 新增字段 ==========
    /** Agent 唯一标识符 */
    private String agentId;
    /** Agent 显示名称 */
    private String agentName;
    /** 工作区目录 */
    private String workspaceDir;
    /** Agent 专属目录 */
    private String agentDir;
    /** 解析后的 Agent 配置（不可变） */
    private lyjew.com.lyclaw.config.ResolvedAgentConfig resolvedConfig;
    /** 思考级别 */
    private String thinkingLevel;
    /** 详细度级别 */
    private String verboseLevel;
    /** 推理级别 */
    private String reasoningLevel;
    /** 运行时类型 */
    private AgentRuntimeType runtimeType = AgentRuntimeType.EMBEDDED;
    /** 运行元数据（runId, jobId, trigger 等）—— 向后兼容的 Map 存储 */
    private final Map<String, Object> runMetadataMap = new java.util.concurrent.ConcurrentHashMap<>();
    /** 类型化运行元数据（子代理层级、模型解析、归档等） */
    private final RunMetadata runMetadata = new RunMetadata();
    /** 当前绑定的Session对象——SessionPersistenceHook设置 */
    private lyjew.com.lyclaw.model.Session session;

    /**
     * 构造 AgentContext。
     */
    public AgentContext(String sessionId, String userMessage, String systemPrompt,
                        ToolRegistry toolRegistry, Method method, Object[] args) {
        this.sessionId = sessionId;
        this.userMessage = userMessage;
        this.systemPrompt = systemPrompt;
        this.toolRegistry = toolRegistry;
        this.method = method;
        this.args = args;
        this.tracing = new TraceContext();
    }

    /**
     * 构造 AgentContext（含 agentId 和 agentName）。
     */
    public AgentContext(String sessionId, String userMessage, String systemPrompt,
                        ToolRegistry toolRegistry, Method method, Object[] args,
                        String agentId, String agentName) {
        this(sessionId, userMessage, systemPrompt, toolRegistry, method, args);
        this.agentId = agentId;
        this.agentName = agentName;
    }

    // ========== Agent 标识 getters/setters ==========

    public String getSessionId() { return sessionId; }

    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String userMessage) { this.userMessage = userMessage; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public ChatRequest getChatRequest() { return chatRequest; }
    public void setChatRequest(ChatRequest chatRequest) { this.chatRequest = chatRequest; }

    public ToolRegistry getToolRegistry() { return toolRegistry; }

    public Method getMethod() { return method; }
    public Object[] getArgs() { return args; }

    public SandboxLevel getSandboxLevel() { return sandboxLevel; }
    public void setSandboxLevel(SandboxLevel sandboxLevel) { this.sandboxLevel = sandboxLevel; }

    public Lifecycle getLifecycle() { return lifecycle; }
    public void setLifecycle(Lifecycle lifecycle) { this.lifecycle = lifecycle; }

    // ========== 追踪 ==========

    public TraceContext getTracing() { return tracing; }

    // ========== 流水线状态 ==========

    public List<String> getToolResults() { return toolResults; }
    public void addToolResult(String result) { toolResults.add(result); }

    public AtomicInteger getSuccessCount() { return successCount; }
    public AtomicInteger getFailCount() { return failCount; }

    public List<TaskNode> getNodes() { return nodes; }
    public void addNode(TaskNode node) { nodes.add(node); }

    public AtomicReference<Double> getReflectScoreRef() { return reflectScoreRef; }

    public AtomicBoolean getPipelineOk() { return pipelineOk; }
    public boolean isPipelineOk() { return pipelineOk.get(); }
    public void setPipelineOk(boolean value) { pipelineOk.set(value); }

    public AtomicLong getRespondStartMs() { return respondStartMs; }

    public AtomicBoolean getTerminated() { return terminated; }
    public boolean isTerminated() { return terminated.get(); }
    public void setTerminated(boolean value) { terminated.set(value); }

    public AtomicReference<String> getCurrentStage() { return currentStage; }

    // ========== 扩展属性 ==========

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) { return (T) attributes.get(key); }
    public void setAttribute(String key, Object value) { attributes.put(key, value); }
    public Map<String, Object> getAttributes() { return attributes; }

    // ========== Phase 1 新增 getters/setters ==========

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getWorkspaceDir() { return workspaceDir; }
    public void setWorkspaceDir(String workspaceDir) { this.workspaceDir = workspaceDir; }

    public String getAgentDir() { return agentDir; }
    public void setAgentDir(String agentDir) { this.agentDir = agentDir; }

    public lyjew.com.lyclaw.config.ResolvedAgentConfig getResolvedConfig() { return resolvedConfig; }
    public void setResolvedConfig(lyjew.com.lyclaw.config.ResolvedAgentConfig resolvedConfig) { this.resolvedConfig = resolvedConfig; }

    public String getThinkingLevel() { return thinkingLevel; }
    public void setThinkingLevel(String thinkingLevel) { this.thinkingLevel = thinkingLevel; this.runMetadata.setThinkingLevel(thinkingLevel); }

    public String getVerboseLevel() { return verboseLevel; }
    public void setVerboseLevel(String verboseLevel) { this.verboseLevel = verboseLevel; this.runMetadata.setVerboseLevel(verboseLevel); }

    public String getReasoningLevel() { return reasoningLevel; }
    public void setReasoningLevel(String reasoningLevel) { this.reasoningLevel = reasoningLevel; this.runMetadata.setReasoningLevel(reasoningLevel); }

    public AgentRuntimeType getRuntimeType() { return runtimeType; }
    public void setRuntimeType(AgentRuntimeType runtimeType) { this.runtimeType = runtimeType; }

    // ========== 运行元数据（Map-based，向后兼容） ==========

    /**
     * 从运行元数据中读取指定 key。
     *
     * <p>对于 {@link RunMetadata} 中的类型化字段，优先返回类型化值；
     * 未知 key 回退到 Map 存储，保证向后兼容。</p>
     */
    public Object getRunMetadata(String key) {
        return switch (key) {
            case "subagentDepth" -> runMetadata.getSubagentDepth();
            case "parentSessionKey" -> runMetadata.getParentSessionKey();
            case "subagentTargetAgentId" -> runMetadata.getSubagentTargetAgentId();
            case "thinkingLevel" -> runMetadata.getThinkingLevel();
            case "verboseLevel" -> runMetadata.getVerboseLevel();
            case "reasoningLevel" -> runMetadata.getReasoningLevel();
            case "resolvedModel" -> runMetadata.getResolvedModel();
            case "resolvedProvider" -> runMetadata.getResolvedProvider();
            case "imageModel" -> runMetadata.getImageModel();
            case "archiveSessionKey" -> runMetadata.getArchiveSessionKey();
            default -> runMetadataMap.get(key);
        };
    }

    /**
     * 写入运行元数据。
     *
     * <p>同时写入 Map（向后兼容）和 {@link RunMetadata} 类型化字段（已知 key）。
     * 类型不匹配时仅写入 Map，不抛出异常。</p>
     */
    public void setRunMetadata(String key, Object value) {
        runMetadataMap.put(key, value);
        syncTypedRunMetadata(key, value);
    }

    /**
     * 将已知 key 的值同步到 {@link RunMetadata} 类型化字段。
     */
    private void syncTypedRunMetadata(String key, Object value) {
        if (value == null) return;
        switch (key) {
            case "subagentDepth" -> {
                if (value instanceof Number n) runMetadata.setSubagentDepth(n.intValue());
            }
            case "parentSessionKey" -> {
                if (value instanceof String s) runMetadata.setParentSessionKey(s);
            }
            case "subagentTargetAgentId" -> {
                if (value instanceof String s) runMetadata.setSubagentTargetAgentId(s);
            }
            case "thinkingLevel" -> {
                if (value instanceof String s) runMetadata.setThinkingLevel(s);
            }
            case "verboseLevel" -> {
                if (value instanceof String s) runMetadata.setVerboseLevel(s);
            }
            case "reasoningLevel" -> {
                if (value instanceof String s) runMetadata.setReasoningLevel(s);
            }
            case "resolvedModel" -> {
                if (value instanceof String s) runMetadata.setResolvedModel(s);
            }
            case "resolvedProvider" -> {
                if (value instanceof String s) runMetadata.setResolvedProvider(s);
            }
            case "imageModel" -> {
                if (value instanceof String s) runMetadata.setImageModel(s);
            }
            case "archiveSessionKey" -> {
                if (value instanceof String s) runMetadata.setArchiveSessionKey(s);
            }
        }
    }

    public Map<String, Object> getRunMetadataMap() { return Collections.unmodifiableMap(runMetadataMap); }

    // ========== 运行元数据（类型化访问） ==========

    /** 返回类型化的 {@link RunMetadata}，提供强类型子代理层级、模型解析等访问。 */
    public RunMetadata getRunMetadata() { return runMetadata; }

    // ========== 活跃子 Agent ==========

    public boolean addActiveSubagent(String agentId) { return this.runMetadata.getActiveSubagentIds().add(agentId); }
    public boolean removeActiveSubagent(String agentId) { return this.runMetadata.getActiveSubagentIds().remove(agentId); }
    public java.util.Set<String> getActiveSubagentIds() { return Collections.unmodifiableSet(this.runMetadata.getActiveSubagentIds()); }
    public int getActiveSubagentCount() { return this.runMetadata.getActiveSubagentIds().size(); }

    // ========== Session 绑定 ==========

    public lyjew.com.lyclaw.model.Session getSession() { return session; }
    public void setSession(lyjew.com.lyclaw.model.Session session) { this.session = session; }

    // ========== 生命周期：检查点 ==========

    /**
     * 将当前上下文序列化为快照 Map，用于 SESSION/PERSISTENT 模式的检查点保存。
     * 包含会话ID、用户消息、Stage 进度、工具结果等关键状态。
     */
    public Map<String, Object> toSnapshot() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("sessionId", sessionId);
        snapshot.put("userMessage", userMessage);
        snapshot.put("systemPrompt", systemPrompt);
        snapshot.put("sandboxLevel", sandboxLevel != null ? sandboxLevel.name() : null);
        snapshot.put("lifecycle", lifecycle.name());
        snapshot.put("currentStage", currentStage.get());
        snapshot.put("successCount", successCount.get());
        snapshot.put("failCount", failCount.get());
        snapshot.put("pipelineOk", pipelineOk.get());
        snapshot.put("terminated", terminated.get());
        snapshot.put("reflectScore", reflectScoreRef.get());
        snapshot.put("toolResults", new ArrayList<>(toolResults));
        snapshot.put("tracing", Map.of("traceId", tracing.getTraceId()));
        // Phase 1 新增字段
        snapshot.put("agentId", agentId);
        snapshot.put("agentName", agentName);
        snapshot.put("workspaceDir", workspaceDir);
        snapshot.put("agentDir", agentDir);
        snapshot.put("thinkingLevel", thinkingLevel);
        snapshot.put("verboseLevel", verboseLevel);
        snapshot.put("reasoningLevel", reasoningLevel);
        snapshot.put("runtimeType", runtimeType != null ? runtimeType.name() : null);
        // RunMetadata 字段
        snapshot.put("subagentDepth", runMetadata.getSubagentDepth());
        snapshot.put("parentSessionKey", runMetadata.getParentSessionKey());
        snapshot.put("subagentTargetAgentId", runMetadata.getSubagentTargetAgentId());
        snapshot.put("resolvedModel", runMetadata.getResolvedModel());
        snapshot.put("resolvedProvider", runMetadata.getResolvedProvider());
        snapshot.put("imageModel", runMetadata.getImageModel());
        snapshot.put("archiveSessionKey", runMetadata.getArchiveSessionKey());
        return snapshot;
    }

    /**
     * 从快照恢复 AgentContext。仅恢复可序列化的关键状态，
     * toolRegistry、method、args 等运行时引用需要调用方重新注入。
     */
    @SuppressWarnings("unchecked")
    public void restoreFromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null) return;
        if (snapshot.get("sandboxLevel") != null) {
            this.sandboxLevel = SandboxLevel.valueOf((String) snapshot.get("sandboxLevel"));
        }
        if (snapshot.get("lifecycle") != null) {
            this.lifecycle = Lifecycle.valueOf((String) snapshot.get("lifecycle"));
        }
        if (snapshot.get("currentStage") != null) {
            this.currentStage.set((String) snapshot.get("currentStage"));
        }
        if (snapshot.get("successCount") != null) {
            this.successCount.set(((Number) snapshot.get("successCount")).intValue());
        }
        if (snapshot.get("failCount") != null) {
            this.failCount.set(((Number) snapshot.get("failCount")).intValue());
        }
        if (snapshot.get("pipelineOk") != null) {
            this.pipelineOk.set((Boolean) snapshot.get("pipelineOk"));
        }
        if (snapshot.get("terminated") != null) {
            this.terminated.set((Boolean) snapshot.get("terminated"));
        }
        if (snapshot.get("reflectScore") != null) {
            this.reflectScoreRef.set(((Number) snapshot.get("reflectScore")).doubleValue());
        }
        if (snapshot.get("toolResults") instanceof List<?> list) {
            this.toolResults.clear();
            for (Object item : list) this.toolResults.add((String) item);
        }
        // Phase 1 新增字段恢复
        if (snapshot.get("agentId") != null) {
            this.agentId = (String) snapshot.get("agentId");
        }
        if (snapshot.get("agentName") != null) {
            this.agentName = (String) snapshot.get("agentName");
        }
        if (snapshot.get("workspaceDir") != null) {
            this.workspaceDir = (String) snapshot.get("workspaceDir");
        }
        if (snapshot.get("agentDir") != null) {
            this.agentDir = (String) snapshot.get("agentDir");
        }
        if (snapshot.get("thinkingLevel") != null) {
            setThinkingLevel((String) snapshot.get("thinkingLevel"));
        }
        if (snapshot.get("verboseLevel") != null) {
            setVerboseLevel((String) snapshot.get("verboseLevel"));
        }
        if (snapshot.get("reasoningLevel") != null) {
            setReasoningLevel((String) snapshot.get("reasoningLevel"));
        }
        if (snapshot.get("runtimeType") != null) {
            this.runtimeType = AgentRuntimeType.valueOf((String) snapshot.get("runtimeType"));
        }
        // RunMetadata 字段恢复
        if (snapshot.get("subagentDepth") != null) {
            this.runMetadata.setSubagentDepth(((Number) snapshot.get("subagentDepth")).intValue());
        }
        if (snapshot.get("parentSessionKey") != null) {
            this.runMetadata.setParentSessionKey((String) snapshot.get("parentSessionKey"));
        }
        if (snapshot.get("subagentTargetAgentId") != null) {
            this.runMetadata.setSubagentTargetAgentId((String) snapshot.get("subagentTargetAgentId"));
        }
        if (snapshot.get("resolvedModel") != null) {
            this.runMetadata.setResolvedModel((String) snapshot.get("resolvedModel"));
        }
        if (snapshot.get("resolvedProvider") != null) {
            this.runMetadata.setResolvedProvider((String) snapshot.get("resolvedProvider"));
        }
        if (snapshot.get("imageModel") != null) {
            this.runMetadata.setImageModel((String) snapshot.get("imageModel"));
        }
        if (snapshot.get("archiveSessionKey") != null) {
            this.runMetadata.setArchiveSessionKey((String) snapshot.get("archiveSessionKey"));
        }
    }

    // ========== 便捷工厂 ==========

    /** 创建 SESSION 生命周期的上下文（跨多次调用共享状态） */
    public static AgentContext sessionScoped(String sessionId, String userMessage,
                                             String systemPrompt, ToolRegistry toolRegistry,
                                             Method method, Object[] args) {
        AgentContext ctx = new AgentContext(sessionId, userMessage, systemPrompt,
                toolRegistry, method, args);
        ctx.setLifecycle(Lifecycle.SESSION);
        return ctx;
    }

    /** 创建 PERSISTENT 生命周期的上下文（重启后仍可恢复） */
    public static AgentContext persistentScoped(String sessionId, String userMessage,
                                                String systemPrompt, ToolRegistry toolRegistry,
                                                Method method, Object[] args) {
        AgentContext ctx = new AgentContext(sessionId, userMessage, systemPrompt,
                toolRegistry, method, args);
        ctx.setLifecycle(Lifecycle.PERSISTENT);
        return ctx;
    }
}
