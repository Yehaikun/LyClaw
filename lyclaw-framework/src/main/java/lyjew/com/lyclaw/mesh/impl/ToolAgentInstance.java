package lyjew.com.lyclaw.mesh.impl;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lyjew.com.lyclaw.mesh.AgentCallHistory;
import lyjew.com.lyclaw.mesh.AgentHandle;
import lyjew.com.lyclaw.mesh.AgentInstance;
import lyjew.com.lyclaw.mesh.AgentLifecycleListener;
import lyjew.com.lyclaw.mesh.AgentLifecycleState;
import lyjew.com.lyclaw.mesh.AgentMessage;
import lyjew.com.lyclaw.mesh.AgentRef;
import lyjew.com.lyclaw.mesh.AgentSpec;
import lyjew.com.lyclaw.mesh.MessageType;
import lyjew.com.lyclaw.mesh.AgentMesh;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 工具 Agent 实例 —— 将单个 Tool 包装为 AgentInstance。
 *
 * <p>核心设计：<strong>工具 = Agent</strong>。
 * 在 Agent Mesh 中，工具和 LLM Agent 实现相同的 AgentInstance 接口。
 * 区别只在于：工具是无状态的、单步执行的；LLM Agent 是有状态的、多步推理的。
 * 这使得调度引擎可以统一处理"调用工具"和"委托给子 Agent"。</p>
 *
 * <p>ToolAgentInstance 通过 {@link #send(AgentMessage)} 接收包含工具参数的
 * REQUEST，执行工具后返回 RESPONSE。</p>
 */
public class ToolAgentInstance implements AgentInstance {

    private static final Logger log = LoggerFactory.getLogger(ToolAgentInstance.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final AgentSpec spec;
    private final AgentHandle handle;
    private final AgentCallHistory callHistory;
    private final AgentMesh mesh;

    private volatile boolean running;

    public ToolAgentInstance(AgentSpec spec, AgentMesh mesh) {
        this.spec = spec;
        this.handle = new AgentHandle();
        this.callHistory = new AgentCallHistory(spec.getAgentId());
        this.mesh = mesh;
        this.handle.setState(AgentLifecycleState.PENDING);
    }

    @Override
    public String getAgentId() { return spec.getAgentId(); }

    @Override
    public AgentRef.AgentType getType() { return AgentRef.AgentType.TOOL; }

    @Override
    public AgentSpec getSpec() { return spec; }

    @Override
    public AgentHandle getHandle() { return handle; }

    @Override
    public AgentCallHistory getCallHistory() { return callHistory; }

    @Override
    public CompletableFuture<AgentMessage> send(AgentMessage message) {
        if (!running) {
            return CompletableFuture.completedFuture(
                    AgentMessage.errorTo(message, "Agent not running: " + getAgentId()));
        }

        handle.incrementTotalRequests();
        handle.setLastActiveTime(System.currentTimeMillis());

        try {
            // 解析参数
            String payload = message.getPayload();
            if (payload == null) payload = "{}";

            // 提取工具名和参数
            String toolName = extractToolName(payload);
            String arguments = payload;

            // 执行工具
            log.debug("ToolAgent executing: {} args={}", toolName, arguments);
            String result = executeTool(toolName, arguments);

            // 记录调用历史
            callHistory.recordCall(toolName, arguments, message.getCorrelationId(), message.getTtlMs());

            AgentMessage response = AgentMessage.responseTo(message, result);
            callHistory.completeCall(message.getCorrelationId(), response);

            return CompletableFuture.completedFuture(response);

        } catch (Exception e) {
            log.error("ToolAgent execution failed: {}", getAgentId(), e);
            handle.incrementErrors();
            AgentMessage error = AgentMessage.errorTo(message, "Tool execution error: " + e.getMessage());
            callHistory.completeCall(message.getCorrelationId(), error);
            return CompletableFuture.completedFuture(error);
        }
    }

    @Override
    public Flux<AgentMessage> sendStream(AgentMessage message) {
        // 工具通常不支持流式，降级为同步
        return Mono.fromFuture(send(message)).flux();
    }

    @Override
    public void start() {
        this.running = true;
        handle.setState(AgentLifecycleState.ACTIVE);
        log.info("ToolAgent started: {}", getAgentId());
    }

    @Override
    public void stop() {
        this.running = false;
        handle.setState(AgentLifecycleState.STOPPED);
        log.info("ToolAgent stopped: {}", getAgentId());
    }

    @Override
    public void destroy() {
        this.running = false;
        handle.setState(AgentLifecycleState.DESTROYED);
        log.info("ToolAgent destroyed: {}", getAgentId());
    }

    @Override
    public AgentLifecycleState getState() { return handle.getState(); }

    @Override
    public void addLifecycleListener(AgentLifecycleListener listener) {
        // 生命周期监听由 mesh 管理
    }

    /**
     * 从 payload 中提取工具名。
     * payload 可以是 {"name": "toolName", "arguments": {...}} 格式。
     */
    private String extractToolName(String payload) {
        try {
            java.util.Map<String, Object> map = objectMapper.readValue(
                    payload, new TypeReference<java.util.LinkedHashMap<String, Object>>() {});
            if (map.containsKey("name")) return map.get("name").toString();
        } catch (Exception ignored) {}
        return spec.getAgentId();
    }

    /**
     * 执行工具调用。
     * 子类可以覆写此方法以实现自定义工具执行逻辑。
     */
    protected String executeTool(String toolName, String arguments) throws Exception {
        // 默认实现：从 spec 的 tools 中查找并执行
        if (spec.getTools() != null) {
            for (lyjew.com.lyclaw.model.ToolDefinition def : spec.getTools()) {
                if (def.getName().equals(toolName)) {
                    // TODO: 当 ToolRegistry 支持 per-agent 作用域后，从 registry 执行
                    return "Tool executed: " + toolName;
                }
            }
        }
        return "Tool result: " + arguments;
    }
}
