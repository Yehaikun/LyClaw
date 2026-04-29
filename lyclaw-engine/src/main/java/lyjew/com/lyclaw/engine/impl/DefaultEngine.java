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
        // 流式请求：直接调 adapter.chatStream()，不走 Pipeline
        // ═══════════════════════════════════════════════════════════
        if (request.isStream()) {
            ModelAdapter adapter = modelProvider.getConfiguredAdapter();
            log.debug("[{}] 流式请求开始", adapter.getProvider());
            return adapter.chatStream(request)
                    .doOnComplete(() ->
                            log.debug("[{}] 流式对话完成", adapter.getProvider()));
        }

        // 1. 读取长期记忆
        MemoryContent memory = memoryManager.read();

        // 2. 获取工具定义
        List<ToolDefinition> toolDefinitions = toolRegistry.getAllDefinitions();

        // ═══════════════════════════════════════════════════════════
        // 3. 从 SessionStorage 加载已有会话（多轮对话支持）
        // ═══════════════════════════════════════════════════════════
        Session session = sessionStorage.get(request.getSessionId()).orElse(null);

        if (session == null) {
            session = new Session();
            session.setSessionId(request.getSessionId());
            session.setMessages(request.getMessages());
        } else {
            List<Message> allMessages = new ArrayList<>(session.getMessages());
            if (request.getMessages() != null) {
                allMessages.addAll(request.getMessages());
            }
            session.setMessages(allMessages);
        }

        // 4. 构建 ChatContext
        ChatContext context = new ChatContext(
                request, session, memory,
                toolDefinitions, interceptorChain, modelProvider
        );

        // 5. 构建并执行 Pipeline
        Pipeline pipeline = pipelineBuilder.build();
        pipeline.execute(context);

        // 6. 获取结果
        ChatResult result = context.getResult();

        // ═══════════════════════════════════════════════════════════
        // 7. 非流式：将模型回复写入 Session 并持久化
        // ═══════════════════════════════════════════════════════════
        // 7. 非流式：将模型回复写入 Session 并持久化
        // ═══════════════════════════════════════════════════════════
        if (result != null) {
            memoryManager.append(result.getContent());

            Message assistantMsg = Message.builder()
                    .role("assistant")
                    .content(result.getContent())
                    .build();

            session.getMessages().add(assistantMsg);

            sessionStorage.save(session);
        }

        return Flux.just(result != null ? result.getContent() : "");
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
}