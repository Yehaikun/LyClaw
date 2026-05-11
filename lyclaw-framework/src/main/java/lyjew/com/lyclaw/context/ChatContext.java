package lyjew.com.lyclaw.context;

import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.InterceptorChain;
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
 * 聊天上下文，聚合了一次对话处理所需的所有运行时数据。
 *
 * 该类是整个框架的请求处理中枢，在一次对话处理流程中携带以下关键信息：
 * 请求参数（ChatRequest）、会话状态（Session）、记忆内容（MemoryContent）、
 * 消息历史（Messages）、可用工具列表（ToolDefinition）、拦截器链（InterceptorChain）、
 * 模型提供者（ModelProvider）、追踪信息（TraceContext）以及可扩展的自定义属性。
 * 上下文在拦截器链、流水线阶段、工具调用等各组件之间流转和共享。
 */
public class ChatContext {

    /** 原始聊天请求，包含提示词、模型参数、流式开关等 */
    private final ChatRequest request;
    /** 当前会话对象，携带会话 ID、消息历史等持久化数据 */
    private Session session;
    /** 持久化记忆内容，用于跨会话的信息保留 */
    private final MemoryContent memory;
    /** 本次对话的消息列表，从会话复制而来，允许在流程中追加新消息 */
    private final List<Message> messages;
    /** 当前可用的工具定义列表 */
    private final List<ToolDefinition> toolDefinitions;
    /** 拦截器链，用于在请求处理前后执行横切逻辑 */
    private final InterceptorChain interceptorChain;
    /** 模型提供者，封装了具体 AI 模型的调用接口 */
    private final ModelProvider modelProvider;
    /** 对话处理结果，在流程末尾设置 */
    private ChatResult result;
    /** 追踪上下文，用于调用链路的追踪和日志关联 */
    private final TraceContext tracing;
    /** 可扩展的自定义属性，用于在不同组件间传递附加数据 */
    private final Map<String, Object> attributes = new HashMap<>();

    /**
     * 构造聊天上下文（自动生成追踪 ID）。
     *
     * @param request          聊天请求
     * @param session          当前会话
     * @param memory           记忆内容
     * @param toolDefinitions  可用工具列表
     * @param interceptorChain 拦截器链
     * @param modelProvider    模型提供者
     */
    public ChatContext(ChatRequest request, Session session,
                       MemoryContent memory, List<ToolDefinition> toolDefinitions,
                       InterceptorChain interceptorChain,
                       ModelProvider modelProvider) {
        this.request = request;
        this.session = session;
        this.memory = memory;
        // 从会话中复制消息列表，避免修改原始会话数据
        this.messages = new ArrayList<>(session.getMessages());
        this.toolDefinitions = toolDefinitions;
        this.interceptorChain = interceptorChain;
        this.modelProvider = modelProvider;
        this.tracing = new TraceContext();
    }

    /**
     * 构造聊天上下文（使用指定的追踪 ID）。
     *
     * @param request          聊天请求
     * @param session          当前会话
     * @param memory           记忆内容
     * @param toolDefinitions  可用工具列表
     * @param interceptorChain 拦截器链
     * @param modelProvider    模型提供者
     * @param traceId          指定的追踪 ID，用于关联日志
     */
    public ChatContext(ChatRequest request, Session session,
                       MemoryContent memory, List<ToolDefinition> toolDefinitions,
                       InterceptorChain interceptorChain,
                       ModelProvider modelProvider, String traceId) {
        this.request = request;
        this.session = session;
        this.memory = memory;
        // 从会话中复制消息列表，避免修改原始会话数据
        this.messages = new ArrayList<>(session.getMessages());
        this.toolDefinitions = toolDefinitions;
        this.interceptorChain = interceptorChain;
        this.modelProvider = modelProvider;
        this.tracing = new TraceContext(traceId);
    }

    public ChatRequest getRequest() { return request; }

    public Session getSession() { return session; }

    public void setSession(Session session) { this.session = session; }

    public MemoryContent getMemory() { return memory; }

    public List<Message> getMessages() { return messages; }

    public List<ToolDefinition> getToolDefinitions() { return toolDefinitions; }

    public InterceptorChain getInterceptorChain() { return interceptorChain; }

    public ModelProvider getModelProvider() { return modelProvider; }

    public ChatResult getResult() { return result; }

    public void setResult(ChatResult result) { this.result = result; }

    public TraceContext getTracing() { return tracing; }

    /**
     * 设置自定义属性，用于在组件间传递扩展数据。
     *
     * @param key   属性名
     * @param value 属性值
     */
    public void setAttribute(String key, Object value) {
        this.attributes.put(key, value);
    }

    /**
     * 获取自定义属性。
     *
     * @param key 属性名
     * @return 属性值，未设置时返回 null
     */
    public Object getAttribute(String key) {
        return this.attributes.get(key);
    }
}
