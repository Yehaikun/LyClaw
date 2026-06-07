package lyjew.com.lyclaw.mesh.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.mesh.AgentCallHistory;
import lyjew.com.lyclaw.mesh.AgentExecutionEvent;
import lyjew.com.lyclaw.mesh.AgentHandle;
import lyjew.com.lyclaw.mesh.AgentInstance;
import lyjew.com.lyclaw.mesh.AgentLifecycleListener;
import lyjew.com.lyclaw.mesh.AgentLifecycleState;
import lyjew.com.lyclaw.mesh.AgentMessage;
import lyjew.com.lyclaw.mesh.AgentRef;
import lyjew.com.lyclaw.mesh.AgentSpec;
import lyjew.com.lyclaw.mesh.AgentMesh;
import lyjew.com.lyclaw.mesh.MessageType;
import lyjew.com.lyclaw.mesh.impl.DefaultAgentMesh;
import lyjew.com.lyclaw.session.SessionService;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.react.ToolExecutor;
import lyjew.com.lyclaw.tool.ToolRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * LLM Agent 实例 —— 消息驱动的 ReAct Agent。
 *
 * <p>接收 {@link AgentMessage}，内部使用 {@link ReActEngine} 执行 LLM 推理。
 * 这是 Agent Mesh 中最核心的 Agent 类型——拥有自己的 system prompt、tools、
 * model 配置，并通过 ReAct 循环与 LLM 交互。</p>
 *
 * <p>在 ReAct 循环中，工具调用和子 Agent 委托通过 {@link AgentMesh} 统一路由：
 * <ul>
 *   <li>工具调用 → mesh.send({to: toolAgentId, payload: args})</li>
 *   <li>子 Agent 委托 → mesh.send({to: childAgentId, payload: task})</li>
 * </ul>
 * 调用记录自动写入 {@link AgentCallHistory}。</p>
 */
public class LLMAgentInstance implements AgentInstance {

    private static final Logger log = LoggerFactory.getLogger(LLMAgentInstance.class);

    private final AgentSpec spec;
    private final AgentHandle handle;
    private final AgentCallHistory callHistory;
    private final ReActEngine reActEngine;
    private final ChatFacade chatFacade;
    private final ToolRegistry toolRegistry;
    private final AgentMesh mesh;

    private volatile boolean running;

    public LLMAgentInstance(AgentSpec spec, ReActEngine reActEngine,
                             ChatFacade chatFacade, ToolRegistry toolRegistry,
                             AgentMesh mesh) {
        this.spec = spec;
        this.handle = new AgentHandle();
        this.callHistory = new AgentCallHistory(spec.getAgentId());
        this.reActEngine = reActEngine;
        this.chatFacade = chatFacade;
        this.toolRegistry = toolRegistry;
        this.mesh = mesh;
        this.handle.setState(AgentLifecycleState.PENDING);
    }

    /** 注入 SessionService（用于跨轮次对话续接） */

    @Override
    public String getAgentId() { return spec.getAgentId(); }

    @Override
    public AgentRef.AgentType getType() { return AgentRef.AgentType.LLM; }

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

        return Mono.fromCallable(() -> {
            try {
                // 1. 构建 ChatRequest
                ChatRequest request = buildChatRequest(message);

                // 2. 构建 ToolExecutor（通过 mesh 路由到工具/子 Agent）
                ToolExecutor toolExecutor = buildMeshToolExecutor(message);

                // 3. 发布执行事件
                publishEvent(AgentExecutionEvent.started(getAgentId(),
                        message.getCorrelationId(), "收到任务: " + truncate(message.getPayload(), 60)));
                publishEvent(AgentExecutionEvent.stage(getAgentId(),
                        message.getCorrelationId(), "ReAct 推理",
                        "开始执行 ReAct 循环", 10));

                // 4. 执行 ReAct 循环（带事件回调）
                handle.setState(AgentLifecycleState.PROGRESS);
                String result = reActEngine.execute(chatFacade, request, toolExecutor,
                        event -> publishEvent(event));
                handle.setState(AgentLifecycleState.ACTIVE);

                publishEvent(AgentExecutionEvent.completed(getAgentId(),
                        message.getCorrelationId(), "执行完成，结果长度="
                                + (result != null ? result.length() : 0) + "字符"));

                // 4. 记录调用历史
                AgentMessage response = AgentMessage.responseTo(message, result);
                if (message.getCorrelationId() != null) {
                    callHistory.recordCall("llm:" + spec.getModel(), message.getPayload(),
                            message.getCorrelationId(), message.getTtlMs());
                    callHistory.completeCall(message.getCorrelationId(), response);
                }

                // 5. 跨轮次对话持久化：保存到 session（仅限用户显式传入 sessionId）
                // Session saving is handled by the API layer (MeshController)

                log.info("LLMAgent {} completed: resultLen={}", getAgentId(),
                        result != null ? result.length() : 0);
                return response;

            } catch (Exception e) {
                log.error("LLMAgent {} failed: {}", getAgentId(), e.getMessage(), e);
                handle.incrementErrors();
                handle.setState(AgentLifecycleState.ACTIVE);
                return AgentMessage.errorTo(message, "LLM agent error: " + e.getMessage());
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .toFuture();
    }

    @Override
    public Flux<AgentMessage> sendStream(AgentMessage message) {
        if (!running) {
            return Flux.just(AgentMessage.errorTo(message, "Agent not running: " + getAgentId()));
        }

        return Flux.defer(() -> {
            ChatRequest request = buildChatRequest(message);
            request.setStream(true);
            ToolExecutor toolExecutor = buildMeshToolExecutor(message);

            return reActEngine.executeStream(chatFacade, request, toolExecutor)
                    .map(event -> {
                        String eventType = event.event();
                        String data = event.data();

                        if ("message".equals(eventType)) {
                            return AgentMessage.builder()
                                    .type(MessageType.STREAM)
                                    .to(message.getFrom())
                                    .from(getAgentId())
                                    .correlationId(message.getCorrelationId())
                                    .payload(data)
                                    .streamEnd(false)
                                    .build();
                        }
                        if ("thinking".equals(eventType)) {
                            return AgentMessage.builder()
                                    .type(MessageType.STREAM)
                                    .metadata("type", "thinking")
                                    .payload(data)
                                    .build();
                        }
                        // 默认透传
                        return AgentMessage.builder()
                                .type(MessageType.STREAM)
                                .payload(eventType + ": " + (data != null ? data : ""))
                                .build();
                    })
                    .concatWith(Flux.just(AgentMessage.builder()
                            .type(MessageType.RESPONSE)
                            .to(message.getFrom())
                            .from(getAgentId())
                            .correlationId(message.getCorrelationId())
                            .streamEnd(true)
                            .build()));
        });
    }

    @Override
    public void start() {
        this.running = true;
        handle.setState(AgentLifecycleState.ACTIVE);
        log.info("LLMAgent started: {} (model={})", getAgentId(), spec.getModel());
    }

    @Override
    public void stop() {
        this.running = false;
        handle.setState(AgentLifecycleState.STOPPED);
        log.info("LLMAgent stopped: {}", getAgentId());
    }

    @Override
    public void destroy() {
        this.running = false;
        handle.setState(AgentLifecycleState.DESTROYED);
        log.info("LLMAgent destroyed: {}", getAgentId());
    }

    @Override
    public AgentLifecycleState getState() { return handle.getState(); }

    @Override
    public void addLifecycleListener(AgentLifecycleListener listener) {
        // 生命周期监听由 mesh 管理
    }

    // ── 内部方法 ──

    /**
     * 根据入站消息和 AgentSpec 构建 ChatRequest。
     */
    private ChatRequest buildChatRequest(AgentMessage message) {
        String sessionId = message.getMetadata() != null
                ? (String) message.getMetadata().get("sessionId") : null;

        List<Message> messages = new ArrayList<>();
        // 系统提示词
        if (spec.getSystemPrompt() != null && !spec.getSystemPrompt().isEmpty()) {
            messages.add(Message.system(spec.getSystemPrompt()));
        }

        // 跨轮次对话续接：加载 session 历史
        if (sessionId != null && getSessionService() != null) {
            List<lyjew.com.lyclaw.model.Message> history = getSessionService().loadLatestMessages(sessionId, 200);
            // 跳过第一条如果是 system（由 buildChatRequest 注入）
            for (lyjew.com.lyclaw.model.Message hMsg : history) {
                if (!"system".equals(hMsg.getRole())) {
                    messages.add(hMsg);
                }
            }
            log.info("Loaded {} history messages from session {}", history.size(), sessionId);
        }

        // 用户消息（如果历史中最后一条不是同样内容的 user 消息）
        String userPayload = message.getPayload() != null ? message.getPayload() : "";
        if (messages.isEmpty() || !"user".equals(messages.get(messages.size() - 1).getRole())) {
            messages.add(Message.user(userPayload));
        }

        ChatRequest request = ChatRequest.builder()
                .sessionId(sessionId != null ? sessionId : UUID.randomUUID().toString().substring(0, 8))
                .messages(messages)
                .model(spec.getModel() != null && !spec.getModel().isEmpty() ? spec.getModel() : null)
                .toolChoice("auto")
                .stream(false)
                .build();

        // 注入 Agent 的工具定义
        if (spec.getTools() != null && !spec.getTools().isEmpty()) {
            request.setTools(spec.getTools());
        } else if (toolRegistry != null) {
            request.setTools(toolRegistry.getAllDefinitions(request));
        }

        return request;
    }

    /**
     * 构建通过 AgentMesh 路由的 ToolExecutor。
     *
     * <p>在 ReAct 循环中，LLM 发起的工具调用通过 mesh.send() 发送到对应的
     * ToolAgent 或子 LLMAgent，而不是直接调用 ToolRegistry。</p>
     */
    private ToolExecutor buildMeshToolExecutor(AgentMessage originalMessage) {
        return (toolName, toolCallId, argumentsJson) -> {
            log.debug("MeshToolExecutor: calling tool/agent '{}' via mesh", toolName);

            // 1. 查找该工具对应的 Agent
            //    先查 toolName 是否匹配某个已注册的 AgentRef
            java.util.Optional<AgentRef> target = mesh.lookup(toolName);
            String targetId;
            if (target.isPresent()) {
                targetId = target.get().getAgentId();
            } else {
                // 目标 Agent 不存在 → 动态创建 ephemeral Agent
                log.info("Creating ephemeral agent on-the-fly: {}", toolName);
                publishEvent(AgentExecutionEvent.subagentSpawn(getAgentId(),
                        originalMessage.getCorrelationId(), toolName,
                        "动态创建子 Agent: " + toolName));
                try {
                    AgentSpec ephemeralSpec = AgentSpec.builder()
                            .agentId(toolName)
                            .type(AgentRef.AgentType.LLM)
                            .model(spec.getModel())
                            .systemPrompt("你是 " + toolName + "。请根据接收到的任务执行并返回结果。")
                            .ephemeral(true)
                            .ttlMs(300_000)
                            .parentId(getAgentId())
                            .build();
                    mesh.register(ephemeralSpec);
                    targetId = toolName;
                } catch (Exception e) {
                    log.warn("Failed to create ephemeral agent {}, falling back to local tool: {}",
                            toolName, e.getMessage());
                    return executeLocalTool(toolName, argumentsJson);
                }
            }

            // 2. 通过 mesh 发送消息
            String correlationId = UUID.randomUUID().toString().substring(0, 12);
            callHistory.recordCall(targetId, toolName + "(" + argumentsJson + ")",
                    correlationId, 300_000);

            AgentMessage response;
            try {
                response = mesh.send(AgentMessage.builder()
                        .type(MessageType.REQUEST)
                        .to(targetId)
                        .from(getAgentId())
                        .correlationId(correlationId)
                        .traceId(originalMessage.getTraceId())
                        .payload(argumentsJson)
                        .ttlMs(300_000)
                        .build()).join();
            } catch (Exception e) {
                callHistory.completeCall(correlationId,
                        AgentMessage.errorTo(AgentMessage.builder().correlationId(correlationId).build(),
                                e.getMessage()));
                return "Error: " + e.getMessage();
            }

            callHistory.completeCall(correlationId, response);

            // 清理 ephemeral Agent
            if (mesh instanceof DefaultAgentMesh) {
                ((DefaultAgentMesh) mesh).checkEphemeralCleanup(toolName);
            }

            if (response.isError()) {
                return "Error: " + response.getPayload();
            }
            return response.getPayload() != null ? response.getPayload() : "";
        };
    }

    /**
     * 回退到本地 ToolRegistry 执行工具。
     */
    /** 发布执行事件 */
    private void publishEvent(AgentExecutionEvent event) {
        if (mesh instanceof DefaultAgentMesh) {
            ((DefaultAgentMesh) mesh).publishExecutionEvent(event);
        }
    }

    private SessionService getSessionService() {
        DefaultAgentMesh dam = DefaultAgentMesh.getDefault();
        return dam != null ? dam.getSessionService() : null;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private String executeLocalTool(String toolName, String argumentsJson) {
        if (toolRegistry == null) {
            return "Error: Tool '" + toolName + "' not found (no ToolRegistry available)";
        }
        try {
            lyjew.com.lyclaw.model.ToolCall toolCall = lyjew.com.lyclaw.model.ToolCall.builder()
                    .toolCallId(UUID.randomUUID().toString().substring(0, 8))
                    .name(toolName)
                    .arguments(argumentsJson)
                    .build();
            lyjew.com.lyclaw.tool.ToolExecutionResult result = toolRegistry.execute(toolCall, null);
            if (result != null && result.isSuccess()) {
                return result.getResult() != null ? result.getResult() : "";
            }
            return "Error: " + (result != null ? result.getError() : "tool not found");
        } catch (Exception e) {
            log.warn("Local tool execution failed: {} {}", toolName, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
}
