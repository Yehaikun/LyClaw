package lyjew.com.lyclaw.engine.impl;

import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.context.ContextBuilder;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.engine.Engine;
import lyjew.com.lyclaw.engine.EngineMetadata;
import lyjew.com.lyclaw.error.ErrorPolicy;
import lyjew.com.lyclaw.event.EventBus;
import lyjew.com.lyclaw.interceptor.impl.InterceptorChain;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.memory.MemoryManager;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.pipeline.Pipeline;
import lyjew.com.lyclaw.pipeline.impl.ContextBuildStage;
import lyjew.com.lyclaw.pipeline.impl.InterceptorStage;
import lyjew.com.lyclaw.pipeline.impl.MetricsStage;
import lyjew.com.lyclaw.pipeline.impl.PipelineBuilder;
import lyjew.com.lyclaw.pipeline.impl.ResponseBuildStage;
import lyjew.com.lyclaw.pipeline.impl.ToolCallLoopStage;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.storage.SessionStorage;
import lyjew.com.lyclaw.tool.ToolCallPolicy;
import lyjew.com.lyclaw.tool.ToolRegistry;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 默认引擎实现 —— engine 层的核心编排入口。
 *
 * <p>DefaultEngine 使用 Pipeline 模式组织对话流程：
 * <ol>
 *   <li>ContextBuildStage — 加载记忆、构建上下文（消息列表初始化）</li>
 *   <li>InterceptorStage — 拦截器预处理</li>
 *   <li>ToolCallLoopStage — 模型调用 + 工具执行循环</li>
 *   <li>MetricsStage — 指标采集</li>
 *   <li>ResponseBuildStage — 构建响应</li>
 * </ol>
 * </p>
 *
 * <p><b>Spring 注入</b>：@Component，核心组件和 PipelineStage 全部通过构造器注入。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Engine
 * @see Pipeline
 */
@Slf4j
@Component
public class DefaultEngine implements Engine {

    private final ContextBuilder contextBuilder;
    private final InterceptorChain interceptorChain;
    private final ModelProvider modelProvider;
    private final ToolRegistry toolRegistry;
    private final ToolCallPolicy toolCallPolicy;
    private final ErrorPolicy errorPolicy;
    private final EventBus eventBus;
    private final MemoryManager memoryManager;
    private final PipelineBuilder pipelineBuilder;
    private final SessionStorage sessionStorage;  // ← 新增：会话持久化

    public DefaultEngine(ContextBuilder contextBuilder,
                         InterceptorChain interceptorChain,
                         ModelProvider modelProvider,
                         ToolRegistry toolRegistry,
                         ToolCallPolicy toolCallPolicy,
                         ErrorPolicy errorPolicy,
                         EventBus eventBus,
                         MemoryManager memoryManager,
                         PipelineBuilder pipelineBuilder,
                         SessionStorage sessionStorage) {   // ← 新增参数
        this.contextBuilder = contextBuilder;
        this.interceptorChain = interceptorChain;
        this.modelProvider = modelProvider;
        this.toolRegistry = toolRegistry;
        this.toolCallPolicy = toolCallPolicy;
        this.errorPolicy = errorPolicy;
        this.eventBus = eventBus;
        this.memoryManager = memoryManager;
        this.pipelineBuilder = pipelineBuilder;
        this.sessionStorage = sessionStorage;               // ← 新增赋值
    }

    @Override
    public String getName() {
        return "default";
    }

    @Override
    public boolean supports(ChatRequest request) {
        return true;
    }

    @Override
    public Flux<String> execute(ChatRequest request) {
        // ═══════════════════════════════════════════════════════════
        // 公共前序：加载长期记忆、工具定义、会话（流式和非流式共用）
        // ═══════════════════════════════════════════════════════════
        MemoryContent memory = memoryManager.read();
        List<ToolDefinition> toolDefinitions = toolRegistry.getAllDefinitions();
        Session session = loadOrCreateSession(request);

        if (request.isStream()) {
            return executeStream(request, session, memory, toolDefinitions);
        }
        return executeSync(request, session, memory, toolDefinitions);
    }

    @Override
    public EngineMetadata getMetadata() {
        return new EngineMetadata(
                getName(),
                "1.0",
                "Default AI Engine",
                List.of("chat"),
                Set.of("chat")
        );
    }

    // ═════════════════════════════════════════════════════════════
    // 私有方法：流式执行、同步执行、会话管理、结果持久化
    // ═════════════════════════════════════════════════════════════

    /**
     * 流式执行路径。
     * <p>不走 Pipeline，直接调 adapter.chatStream() 返回 SSE 流。
     * 使用 ContextBuilder 构建完整上下文（含记忆+工具定义+会话历史），
     * 在 doOnComplete 中异步保存回复到会话和记忆。</p>
     */
    private Flux<String> executeStream(ChatRequest request, Session session,
                                       MemoryContent memory,
                                       List<ToolDefinition> toolDefinitions) {
        // 构建完整上下文
        List<Message> fullMessages = contextBuilder.buildContext(session, memory, toolDefinitions);
        request.setMessages(fullMessages);

        ModelAdapter adapter = modelProvider.getConfiguredAdapter();
        log.debug("[{}] 流式请求开始", adapter.getProvider());

        StringBuilder collector = new StringBuilder();

        return adapter.chatStream(request)
                .doOnNext(collector::append)
                .publishOn(Schedulers.boundedElastic())
                .doOnComplete(() -> {
                    String content = collector.toString();
                    // 会话持久化
                    saveAssistantMessage(session, content, adapter.getModel());
                    memoryManager.append(content);
                    log.debug("[{}] 流式对话完成", adapter.getProvider());
                })
                .doOnError(error -> {
                    log.error("[{}] 流式对话失败", adapter.getProvider(), error);
                    memoryManager.append("[流式对话失败] " + error.getMessage());
                });
    }

    /**
     * 同步执行路径。
     * <p>走 Pipeline 完整流程：ContextBuild → Interceptor → ToolCallLoop → Metrics → ResponseBuild。
     * Pipeline 内部的 ContextBuildStage 会再次调用 contextBuilder.buildContext() 构建上下文，
     * 所以这里不需要提前构建（ChatContext 构造时传入了 session/memory/toolDefinitions，ContextBuildStage 会处理）。</p>
     */
    private Flux<String> executeSync(ChatRequest request, Session session,
                                     MemoryContent memory,
                                     List<ToolDefinition> toolDefinitions) {
        // 构建 ChatContext
        ChatContext context = new ChatContext(
                request, session, memory,
                toolDefinitions, interceptorChain, modelProvider
        );

        // 执行 Pipeline
        Pipeline pipeline = pipelineBuilder.build();
        pipeline.execute(context);

        // 获取结果
        ChatResult result = context.getResult();
        String content = result != null ? result.getContent() : "";

        if (!content.isEmpty()) {
            ModelAdapter adapter = modelProvider.getConfiguredAdapter();
            saveAssistantMessage(session, content, adapter.getModel());
            memoryManager.append(content);
        }

        return Flux.just(content);
    }

    /**
     * 加载或创建会话。
     * <p>已有会话：加载历史消息并追加当前请求消息。
     * 新会话：用当前请求消息初始化。</p>
     */
    private Session loadOrCreateSession(ChatRequest request) {
        return sessionStorage.get(request.getSessionId())
                .map(existingSession -> {
                    List<Message> allMessages = new ArrayList<>(existingSession.getMessages());
                    if (request.getMessages() != null) {
                        allMessages.addAll(request.getMessages());
                    }
                    existingSession.setMessages(allMessages);
                    return existingSession;
                })
                .orElseGet(() -> {
                    Session newSession = new Session();
                    newSession.setSessionId(request.getSessionId());
                    newSession.setMessages(request.getMessages() != null
                            ? new ArrayList<>(request.getMessages())
                            : new ArrayList<>());
                    return newSession;
                });
    }

    /**
     * 将 assistant 消息写入会话并持久化。
     */
    private void saveAssistantMessage(Session session, String content, String model) {
        log.debug("开始消息持久化");
        Message assistantMsg = Message.builder()
                .role("assistant")
                .content(content)
                .model(model)
                .createdAt(LocalDateTime.now())
                .build();
        session.getMessages().add(assistantMsg);
        sessionStorage.save(session);
    }
}