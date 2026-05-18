package lyjew.com.lyclaw.react;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * <p>合并了原 PipelineContext（流水线状态）和 ChatContext（聊天上下文）的全部字段，
 * 作为 Stage 管线、AgentHook 链、ReAct 循环之间的统一数据总线。</p>
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
    private final List<String> toolResults = new ArrayList<>();
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private final List<TaskNode> nodes = new ArrayList<>();
    private final AtomicReference<Double> reflectScoreRef = new AtomicReference<>(0.0);
    private final AtomicBoolean pipelineOk = new AtomicBoolean(false);
    private final AtomicLong respondStartMs = new AtomicLong();
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private final AtomicReference<String> currentStage = new AtomicReference<>("init");

    // ========== 扩展属性 ==========
    private final Map<String, Object> attributes = new HashMap<>();

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
}
