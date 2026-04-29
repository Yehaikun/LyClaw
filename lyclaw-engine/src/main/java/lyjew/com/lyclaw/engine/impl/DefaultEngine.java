package lyjew.com.lyclaw.engine.impl;

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
        // 1. 读取长期记忆
        MemoryContent memory = memoryManager.read();

        // 2. 获取工具定义
        List<ToolDefinition> toolDefinitions = toolRegistry.getAllDefinitions();

        // ═══════════════════════════════════════════════════════════
        // 3. 从 SessionStorage 加载已有会话（多轮对话支持）
        // ═══════════════════════════════════════════════════════════
        // 目的：同一个 sessionId 代表同一次对话。
        // 第 1 次调用时 sessionId 对应的 JSON 文件还不存在
        // → sessionStorage.get() 返回 Optional.empty() → orElse(null) 返回 null
        // → 走"新会话"分支
        // 第 2 次及以后调用时 JSON 文件已存在
        // → 拿到之前保存的完整 Message 列表（user + assistant 交替）
        // → 走"已有会话"分支，合并历史
        //
        // 如果不做这一步，每次 execute() 都是孤立的单轮调用，
        // ContextBuildStage 组装消息时只有当前这次传入的消息，
        // 模型永远不会看到之前说过什么，多轮对话必然"失忆"。
        Session session = sessionStorage.get(request.getSessionId()).orElse(null);

        if (session == null) {
            // 3a. 新会话：用 request.getMessages() 初始化
            // request.getMessages() 至少包含 1 条 user 消息（由调用方保证）
            session = new Session();
            session.setSessionId(request.getSessionId());
            session.setMessages(request.getMessages());
        } else {
            // 3b. 已有会话：保留历史消息，追加当前请求的新消息
            // 必须把历史（session.getMessages()）和当前（request.getMessages()）合并。
            // 假设历史是 [user: "记住Java", assistant: "好的"]
            // 当前请求是 [user: "我喜欢什么语言？"]
            // 合并后 = [user: "记住Java", assistant: "好的", user: "我喜欢什么语言？"]
            // 这 3 条一起发给模型，模型才知道上下文。
            List<Message> allMessages = new ArrayList<>(session.getMessages());
            if (request.getMessages() != null) {
                allMessages.addAll(request.getMessages());
            }
            session.setMessages(allMessages);
        }

        // 4. 构建 ChatContext — 6 参数构造器
        // session 此时已包含 (历史消息 + 当前消息) 的完整列表
        ChatContext context = new ChatContext(
                request, session, memory,
                toolDefinitions, interceptorChain, modelProvider
        );

        // 5. 使用注入的 PipelineBuilder 构建 Pipeline（已经在 EngineAutoConfiguration 中装配好了5个阶段）
        Pipeline pipeline = pipelineBuilder.build();

        // 6. 执行 Pipeline
        pipeline.execute(context);

        // 7. 获取结果
        ChatResult result = context.getResult();

        // ═══════════════════════════════════════════════════════════
        // 8. 将模型回复写入 Session 并持久化（多轮对话基础）
        // ═══════════════════════════════════════════════════════════
        // 目的：
        //   a) 把 AI 回复以 assistant 角色追加到 session.messages
        //   b) sessionStorage.save() 写入 JSON 文件
        //
        // 持久化后的 session.messages = [user, assistant, user, assistant, ...]
        // 下次 execute() 加载同一个 sessionId 时，能读到完整的历史。
        //
        // 如果漏掉 save()，Session JSON 文件里永远只有 user 消息，
        // 第 3 次调用时加载到的仍然是 [user, user]，
        // 中间的 assistant 回复全部丢失，模型"失忆"。
        if (result != null) {
            memoryManager.append(result.getContent());

            // 8a. 构造 assistant 消息（模型回复），追加到 session.messages
            Message assistantMsg = Message.builder()
                    .role("assistant")
                    .content(result.getContent())
                    .build();
            session.getMessages().add(assistantMsg);

            // 8b. 持久化到 JSON 文件
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