package lyjew.com.lyclaw.context;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.InterceptorChain;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tracing.TraceContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天上下文（Chat Context），聚合了一次完整的 AI 对话处理流程所需的全部运行时数据和组件引用。
 *
 * <p>ChatContext 是 LyClaw 框架请求处理层的中枢数据容器，它将一次对话处理流程中涉及
 * 的所有关键对象集中管理，在各处理组件（拦截器、路由策略、流水线阶段、工具执行器、
 * 记忆检索器）之间作为共享上下文流转和传递。这种集中式的上下文设计避免了各组件之间
 * 需要分别持有和传递多个独立参数的复杂性，统一了数据访问接口，简化了组件的签名和耦合度。
 *
 * <p>ChatContext 承载的核心数据和组件包括：
 * <ul>
 *   <li><b>ChatRequest（聊天请求）</b>：原始的用户请求对象，包含消息历史、系统提示词、
 *       模型参数（temperature、maxTokens）、流式开关、工具定义列表等完整请求信息。
 *       各处理组件通过此字段获取用户意图和请求配置</li>
 *   <li><b>Session（会话）</b>：当前对话的持久化会话对象，携带会话 ID、会话级别的
 *       消息历史、会话元数据等。构造时将其消息列表复制到 messages 字段中供处理流程使用，
 *       流程结束后更新后的消息列表可回写到 Session</li>
 *   <li><b>Memory（记忆）</b>：TODO: 记忆系统待重新设计，届时注入相关记忆内容</li>
 *   <li><b>Messages（消息列表）</b>：本次对话的完整消息历史，从 Session 复制而来，
 *       允许在流程中动态追加新的消息（包括助手回复、工具调用消息、系统消息等）</li>
 *   <li><b>ToolDefinitions（工具定义列表）</b>：当前对话中可用的工具定义。AI 模型
 *       会根据这些定义决定是否需要调用工具以及调用哪个工具。工具定义可来自全局配置
 *       或特定的注解声明</li>
 *   <li><b>InterceptorChain（拦截器链）</b>：请求处理前后的横切关注点处理器链。
 *       拦截器可以在请求进入模型调用前做预处理（如内容审核、Token 限制检查），
 *       也可以在响应返回后做后处理（如响应格式化、敏感信息过滤）</li>
 *   <li><b>ChatFacade（聊天门面）</b>：封装了具体 AI 模型调用接口的门面对象。
 *       通过 ChatFacade，各组件无需关心底层是哪个 Provider、哪个模型，只需调用
 *       统一的 chat() 或 stream() 方法即可</li>
 *   <li><b>TraceContext（追踪上下文）</b>：用于分布式链路追踪的上下文对象，
 *       包含 traceId、spanId、分段耗时统计等信息。构造时自动生成新的追踪 ID
 *       （或使用指定的 traceId），贯穿整个请求处理生命周期</li>
 *   <li><b>Attributes（自定义属性）</b>：一个可扩展的键值对 Map，用于在不同组件间
 *       传递非标准化的自定义数据。任何组件都可以通过 setAttribute/getAttribute
 *       方法存取自定义属性，提供了极大的扩展灵活性，无需修改 ChatContext 类本身</li>
 * </ul>
 *
 * <p>ChatContext 在构造时自动将 Session 的消息列表深拷贝到 messages 字段（通过
 * new ArrayList），确保对 messages 的修改不会意外影响原始 Session 中的数据。
 * 同时自动创建 TraceContext 实例（如果未指定已存在的 traceId），实现全链路追踪。
 *
 * @see lyjew.com.lyclaw.model.ChatRequest
 * @see lyjew.com.lyclaw.chat.ChatFacade
 * TODO: 记忆系统重新设计后恢复 @see lyjew.com.lyclaw.memory.MemoryContent
 * @see lyjew.com.lyclaw.tracing.TraceContext
 */
public class ChatContext {

    /** 原始聊天请求，包含提示词、模型参数、流式开关等 */
    private final ChatRequest request;
    /** 当前会话对象，携带会话 ID、消息历史等持久化数据 */
    private Session session;
    /** TODO: 记忆系统重新设计后恢复 MemoryContent 字段 */
    /** 本次对话的消息列表，从会话复制而来，允许在流程中追加新消息 */
    private final List<Message> messages;
    /** 当前可用的工具定义列表 */
    private final List<ToolDefinition> toolDefinitions;
    /** 拦截器链，用于在请求处理前后执行横切逻辑 */
    private final InterceptorChain interceptorChain;
    /** 聊天门面，封装了具体 AI 模型的调用接口 */
    private final ChatFacade chatFacade;
    /** 对话处理结果，在流程末尾设置 */
    private ChatResult result;
    /** 追踪上下文，用于调用链路的追踪和日志关联 */
    private final TraceContext tracing;
    /** 可扩展的自定义属性，用于在不同组件间传递附加数据 */
    private final Map<String, Object> attributes = new HashMap<>();

    /**
     * 构造聊天上下文（自动生成追踪 ID）——使用新框架 ChatFacade。
     *
     * @param request          聊天请求
     * @param session          当前会话
     * @param toolDefinitions  可用工具列表
     * @param interceptorChain 拦截器链
     * @param chatFacade       聊天门面
     */
    public ChatContext(ChatRequest request, Session session,
                       List<ToolDefinition> toolDefinitions,
                       InterceptorChain interceptorChain,
                       ChatFacade chatFacade) {
        this.request = request;
        this.session = session;
        this.messages = new ArrayList<>(session.getMessages());
        this.toolDefinitions = toolDefinitions;
        this.interceptorChain = interceptorChain;
        this.chatFacade = chatFacade;
        this.tracing = new TraceContext();
    }

    /**
     * 构造聊天上下文（使用指定的追踪 ID）——使用新框架 ChatFacade。
     *
     * @param request          聊天请求
     * @param session          当前会话
     * @param toolDefinitions  可用工具列表
     * @param interceptorChain 拦截器链
     * @param chatFacade       聊天门面
     * @param traceId          指定的追踪 ID，用于关联日志
     */
    public ChatContext(ChatRequest request, Session session,
                       List<ToolDefinition> toolDefinitions,
                       InterceptorChain interceptorChain,
                       ChatFacade chatFacade, String traceId) {
        this.request = request;
        this.session = session;
        this.messages = new ArrayList<>(session.getMessages());
        this.toolDefinitions = toolDefinitions;
        this.interceptorChain = interceptorChain;
        this.chatFacade = chatFacade;
        this.tracing = new TraceContext(traceId);
    }

    public ChatRequest getRequest() { return request; }

    public Session getSession() { return session; }

    public String getSessionId() { return session != null ? session.getSessionId() : null; }

    public void setSession(Session session) { this.session = session; }

    // TODO: 记忆系统重新设计后恢复 getMemory()

    public List<Message> getMessages() { return messages; }

    public List<ToolDefinition> getToolDefinitions() { return toolDefinitions; }

    public InterceptorChain getInterceptorChain() { return interceptorChain; }

    /** @return 聊天门面 */
    public ChatFacade getChatFacade() { return chatFacade; }

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
