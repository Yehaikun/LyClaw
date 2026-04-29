package lyjew.com.lyclaw.context;

import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.impl.InterceptorChain;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.tracing.TraceContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话上下文 —— 贯穿整个管道的唯一数据载体。
 *
 * <p>在 Pipeline 执行过程中，所有阶段共享同一个 ChatContext 实例。
 * 包含原始请求、会话信息、记忆、消息列表、工具列表、模型提供商、
 * 拦截器链、链路追踪上下文等所有 Pipeline 执行所需的状态。</p>
 *
 * <p><b>设计动机</b>：如果不使用 ChatContext，PipelineStage 的方法签名会变成
 * {@code process(ChatRequest, Session, MemoryContent, List<ToolDefinition>,
 * InterceptorChain, Chain, ...)} —— 参数爆炸且难以扩展。
 * ChatContext 将所有相关数据集中管理，新增任何共享数据只需要在 ChatContext
 * 中加一个字段，不影响现有方法签名。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>Pipeline.execute(ChatContext) — 管道执行的入参</li>
 *   <li>PipelineStage.process(ChatContext, Chain) — 阶段处理参数</li>
 *   <li>Interceptor.preHandle(ChatContext) — 拦截器读写的共享上下文</li>
 *   <li>Tool.execute(ToolCall, ChatContext) — 工具执行时可读取会话和请求信息</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class ChatContext {

    /** 原始对话请求，包含用户消息和配置参数 */
    private final ChatRequest request;

    /** 当前会话，包含消息历史和会话元信息。可通过 setter 更新 */
    private Session session;

    /** 长期记忆内容（从 MemoryManager 读取） */
    private final MemoryContent memory;

    /** 消息列表（从会话中提取的只读视图），用于 ContextBuilder 构建模型输入 */
    private final List<Message> messages;

    /** 当前可用的工具定义列表 */
    private final List<ToolDefinition> toolDefinitions;

    /** 拦截器链管理器，preHandle/postHandle 统一入口 */
    private final InterceptorChain interceptorChain;

    /**
     * 模型提供商 —— 提供模型适配器的获取入口。
     * TODO: ModelProvider 接口定义在 lyclaw-engine → lyjew.com.lyclaw.provider，
     * 当前为占位引用。需确保依赖注入时正确注入。
     */
    private final ModelProvider modelProvider;

    /** 对话处理结果（由 ResponseBuildStage 填充） */
    private ChatResult result;

    /** 全链路追踪上下文 */
    private final TraceContext tracing;

    /** 扩展属性映射 —— 任意阶段都可以存入自定义数据 */
    private final Map<String, Object> attributes = new HashMap<>();

    /**
     * 构造一个 ChatContext 实例。
     *
     * @param request          原始对话请求
     * @param session          当前会话
     * @param memory           长期记忆
     * @param toolDefinitions  工具定义列表
     * @param interceptorChain 拦截器链
     * @param modelProvider    模型提供商
     */
    public ChatContext(ChatRequest request, Session session,
                       MemoryContent memory, List<ToolDefinition> toolDefinitions,
                       InterceptorChain interceptorChain, ModelProvider modelProvider) {
        this.request = request;
        this.session = session;
        this.memory = memory;
        this.messages = new ArrayList<>(session.getMessages());
        this.toolDefinitions = toolDefinitions;
        this.interceptorChain = interceptorChain;
        this.modelProvider = modelProvider;
        this.tracing = new TraceContext();
    }

    /** @return 原始对话请求 */
    public ChatRequest getRequest() { return request; }

    /** @return 当前会话 */
    public Session getSession() { return session; }

    /** @param session 更新当前会话 */
    public void setSession(Session session) { this.session = session; }

    /** @return 长期记忆 */
    public MemoryContent getMemory() { return memory; }

    /** @return 消息列表（会话消息的快照） */
    public List<Message> getMessages() { return messages; }

    /** @return 工具定义列表 */
    public List<ToolDefinition> getToolDefinitions() { return toolDefinitions; }

    /** @return 拦截器链 */
    public InterceptorChain getInterceptorChain() { return interceptorChain; }

    /** @return 模型提供商 */
    public ModelProvider getModelProvider() { return modelProvider; }

    /** @return 对话处理结果 */
    public ChatResult getResult() { return result; }

    /** @param result 设置对话处理结果（由 ResponseBuildStage 调用） */
    public void setResult(ChatResult result) { this.result = result; }

    /** @return 全链路追踪上下文 */
    public TraceContext getTracing() { return tracing; }

    /**
     * 设置扩展属性。任何阶段都可以通过此方法存入自定义数据，
     * 其他阶段通过 {@link #getAttribute(String)} 读取。
     *
     * @param key   属性键
     * @param value 属性值
     */
    public void setAttribute(String key, Object value) {
        this.attributes.put(key, value);
    }

    /**
     * 获取扩展属性。
     *
     * @param key 属性键
     * @return 属性值，不存在返回 null
     */
    public Object getAttribute(String key) {
        return this.attributes.get(key);
    }
}