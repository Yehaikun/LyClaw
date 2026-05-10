package lyjew.com.lyclaw.engine.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.engine.Engine;
import lyjew.com.lyclaw.engine.EngineMetadata;
import lyjew.com.lyclaw.interceptor.InterceptorChain;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.memory.MemoryManager;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.pipeline.Pipeline;
import lyjew.com.lyclaw.pipeline.impl.PipelineBuilder;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.storage.SessionStorage;
import lyjew.com.lyclaw.tool.ToolRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 默认引擎实现 —— engine 层的核心编排入口。
 *
 * <p>DefaultEngine 使用 Pipeline 模式组织对话流程。Pipeine 由
 * {@link PipelineBuilder} 自动构建——只需新建 {@code @Component}
 * 实现的 {@code PipelineStage} 并设置正确的 {@code getOrder()} 顺序，
 * Spring 启动时自动收集并按序注册，无需修改 DefaultEngine。</p>
 *
 * <p>流式和非流式路径<b>共享同一个 Pipeline</b>：
 *
 * <ol>
 *   <li>ContextBuildStage — 加载记忆、构建上下文</li>
 *   <li>InterceptorStage — 拦截器预处理</li>
 *   <li>ToolCallLoopStage — 模型调用 + 工具执行循环（流式/非流式统一）</li>
 *   <li>MetricsStage — 指标采集</li>
 *   <li>ResponseBuildStage — 构建响应 + 持久化（流式通过 doOnComplete 异步执行）</li>
 * </ol>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Engine
 * @see Pipeline
 * @see PipelineBuilder
 */
@Slf4j
@Component
public class DefaultEngine implements Engine {

    private final MemoryManager memoryManager;
    private final SessionStorage sessionStorage;
    private final ToolRegistry toolRegistry;
    private final ModelProvider modelProvider;
    private final InterceptorChain interceptorChain;

    /** 由 PipelineBuilder 自动构建的单例 Pipeline */
    private final Pipeline pipeline;

    public DefaultEngine(MemoryManager memoryManager,
                         SessionStorage sessionStorage,
                         ToolRegistry toolRegistry,
                         ModelProvider modelProvider,
                         InterceptorChain interceptorChain,
                         PipelineBuilder pipelineBuilder) {
        this.memoryManager = memoryManager;
        this.sessionStorage = sessionStorage;
        this.toolRegistry = toolRegistry;
        this.modelProvider = modelProvider;
        this.interceptorChain = interceptorChain;

        // PipelineBuilder 已通过 Spring 自动发现所有 PipelineStage，
        // 按 getOrder() 排序注册。这里只需 build() 获取已构建的 Pipeline。
        this.pipeline = pipelineBuilder.build();
        log.info("[DefaultEngine] Pipeline 已就绪 ({} 个 Stage)", pipeline.getStages().size());
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
        log.info("[DefaultEngine] 开始处理请求 (stream={})", request.isStream());
        MemoryContent memory = memoryManager.read();
        List<ToolDefinition> toolDefinitions = toolRegistry.getAllDefinitions();
        Session session = loadOrCreateSession(request);

        if (session.getId() == null && session.getSessionId() != null) {
            session.setId(session.getSessionId());
        }

        ChatContext context = new ChatContext(
                request, session, memory,
                toolDefinitions, interceptorChain, modelProvider
        );

        if (request.isStream()) {
            // 流式路径：Sinks 缓存数据，pipeline 在后台线程执行，主线程立即返回 Flux
            Sinks.Many<String> realtimeSink = Sinks.many().replay().all();
            context.setAttribute("__realtime_sink__", realtimeSink);
            log.info("[DefaultEngine] 流式模式：Pipeline 在后台线程执行");

            CompletableFuture.runAsync(() -> {
                try {
                    pipeline.execute(context);
                } catch (Exception e) {
                    log.error("[DefaultEngine] Pipeline 后台执行异常", e);
                    realtimeSink.tryEmitError(e);
                }
            });

            return realtimeSink.asFlux();
        }

        // 非流式路径：同步执行 Pipeline
        log.info("[DefaultEngine] 同步模式：Pipeline 开始执行 ({} 个 Stage)", pipeline.getStages().size());
        pipeline.execute(context);
        return handleSyncResult(context);
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

    private Flux<String> handleSyncResult(ChatContext context) {
        ChatResult result = context.getResult();
        String content = result != null ? result.getContent() : "";
        log.info("[DefaultEngine] 同步结果: contentLen={}", content.length());
        return Flux.just(content);
    }

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
}
