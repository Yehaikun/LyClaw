package lyjew.com.lyclaw.chat;

import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;
import lyjew.com.lyclaw.model.ToolDefinition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ChatFacade 接口的默认实现，作为整个聊天框架的门面（Facade），聚合了模型路由、
 * 模型注册和模型调用三大核心能力，为上层应用提供统一的对话交互入口。
 *
 * <p>在架构层面，DefaultChatFacade 是框架中应用代码与底层模型适配层之间的桥梁。
 * 它持有 {@link ChatModelRegistry}（模型注册中心，管理所有已注册的 AI 模型实例）和
 * {@link ModelRouter}（路由策略，根据请求特征决定使用哪个模型），并内置了
 * {@link ChatClient} 的默认实现（{@code DefaultChatClient}），支持流畅的链式调用
 * API（Builder 模式）来构建聊天请求。应用开发者无需直接接触底层 ChatModel 接口，
 * 通过 ChatFacade 即可完成从请求构建、路由决策到模型调用的完整链路。
 *
 * <p>核心能力包括：
 * <ul>
 *   <li>{@link #chat(ChatRequest)} — 同步对话：先通过路由策略决策目标模型，再调用
 *       model.call() 同步获取完整回复。同时记录路由决策的详细日志（Provider、Model、
 *      Tier、决策原因），便于问题排查</li>
 *   <li>{@link #chat()} — 返回 {@link ChatClient} 构建器，支持链式调用：
 *       {@code facade.chat().prompt().user("hello").temperature(0.7).call()}
 *       或 {@code ...stream()} 实现流式对话。构建器内部聚合所有参数后自动执行路由
 *       决策并调用模型</li>
 *   <li>{@link #route(ChatRequest, Object)} — 执行路由策略，根据请求内容和上下文
 *       返回路由决策（包含 Provider 名称、模型名称、路由层级和决策原因）</li>
 *   <li>{@link #resolveModel(RoutingDecision)} — 根据路由决策从注册中心解析具体的
 *       ChatModel 实例</li>
 *   <li>{@link #countTokens(String, String, String)} — 通过指定 Provider 的模型
 *       计算文本的 Token 数量，模型不可用时回退到字符数估算</li>
 *   <li>{@link #healthCheck()} — 对所有已注册的模型执行连通性健康检查，返回
 *       以 "provider:model" 为键、布尔值为值的健康状态映射</li>
 * </ul>
 *
 * <p>ChatClient 内部实现（DefaultChatClient 和 DefaultChatRequestBuilder）使用
 * Builder 模式，支持 user()、system()、messages()、tools()、temperature()、
 * maxTokens()、thinking()、option() 等链式方法来逐步构建 ChatRequest，最后通过
 * call() 或 stream() 触发实际调用。Builder 在 call()/stream() 内部自动执行路由
 * 决策，确保每次调用都经过路由策略。
 *
 * @see ChatFacade
 * @see ChatModelRegistry
 * @see ModelRouter
 * @see ChatClient
 */
public class DefaultChatFacade implements ChatFacade {

    private static final Logger log = LoggerFactory.getLogger(DefaultChatFacade.class);

    private final ChatModelRegistry registry;
    private final ModelRouter router;
    private final ChatClient defaultClient;

    /**
     * 构造 DefaultChatFacade，注入模型注册中心和路由策略。
     *
     * <p>构造时同时初始化默认的 ChatClient 实例（DefaultChatClient），该实例内部持有
     * 对当前 facade 的引用以执行路由和模型解析。如果提供的 router 为 null，后续的
     * route() 调用将抛出 NullPointerException，因此调用者应确保传入有效的路由策略实例。
     *
     * @param registry 模型注册中心，管理所有已注册的 AI 模型实例，不可为 null
     * @param router   路由策略，根据请求特征决策使用哪个模型，不可为 null
     */
    public DefaultChatFacade(ChatModelRegistry registry, ModelRouter router) {
        this.registry = registry;
        this.router = router;
        this.defaultClient = new DefaultChatClient(this);
    }

    @Override
    public ChatModel resolveModel(RoutingDecision decision) {
        ChatModel model = registry.resolve(decision);
        if (model == null) {
            throw new IllegalStateException(
                    "未找到模型: provider=" + decision.provider() + ", model=" + decision.model());
        }
        return model;
    }

    @Override
    public Map<String, List<ChatModel>> getModels() {
        return registry.getAll();
    }

    @Override
    public List<String> getAvailableModels(String provider) {
        return registry.getModelNames(provider);
    }

    @Override
    public ChatClient chat() {
        return defaultClient;
    }

    @Override
    public ModelResponse chat(ChatRequest request) {
        RoutingDecision decision = route(request, null);
        ChatModel model = resolveModel(decision);
        log.debug("路由决策: {}:{} (tier={}, reason={})",
                decision.provider(), decision.model(), decision.tier(), decision.reason());
        return model.call(request);
    }

    @Override
    public RoutingDecision route(ChatRequest request, Object context) {
        return router.route(request, context);
    }

    @Override
    public void switchRouter(String routerName) {
        throw new UnsupportedOperationException("运行时切换路由策略暂未支持");
    }

    @Override
    public int countTokens(String provider, String modelName, String text) {
        ChatModel model = registry.resolve(provider, modelName);
        if (model == null) {
            log.warn("countTokens: 未找到模型 {}:{}，回退到字符估算", provider, modelName);
            return text.length() / 2;
        }
        return model.countTokens(text);
    }

    @Override
    public Map<String, Boolean> healthCheck() {
        Map<String, Boolean> results = new LinkedHashMap<>();
        for (Map.Entry<String, List<ChatModel>> entry : registry.getAll().entrySet()) {
            for (ChatModel model : entry.getValue()) {
                String key = entry.getKey() + ":" + model.model();
                try {
                    results.put(key, Boolean.TRUE.equals(model.validate().block()));
                } catch (Exception e) {
                    results.put(key, false);
                }
            }
        }
        return results;
    }

    // ── ChatClient 内部实现 ──

    private static class DefaultChatClient implements ChatClient {

        private final DefaultChatFacade facade;

        DefaultChatClient(DefaultChatFacade facade) {
            this.facade = facade;
        }

        @Override
        public ChatRequestBuilder prompt() {
            return new DefaultChatRequestBuilder(facade);
        }
    }

    private static class DefaultChatRequestBuilder implements ChatClient.ChatRequestBuilder {

        private final DefaultChatFacade facade;
        private final List<Message> messages = new ArrayList<>();
        private String systemPrompt;
        private List<ToolDefinition> tools;
        private Double temperature;
        private Integer maxTokens;
        private boolean thinking;
        private final Map<String, Object> options = new HashMap<>();

        DefaultChatRequestBuilder(DefaultChatFacade facade) {
            this.facade = facade;
        }

        @Override
        public ChatClient.ChatRequestBuilder user(String message) {
            messages.add(Message.user(message));
            return this;
        }

        @Override
        public ChatClient.ChatRequestBuilder system(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                messages.add(0, Message.system(systemPrompt));
            }
            return this;
        }

        @Override
        public ChatClient.ChatRequestBuilder messages(List<Message> messages) {
            this.messages.addAll(messages);
            return this;
        }

        @Override
        public ChatClient.ChatRequestBuilder tools(List<ToolDefinition> tools) {
            this.tools = tools;
            return this;
        }

        @Override
        public ChatClient.ChatRequestBuilder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        @Override
        public ChatClient.ChatRequestBuilder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        @Override
        public ChatClient.ChatRequestBuilder thinking(boolean enabled) {
            this.thinking = enabled;
            return this;
        }

        @Override
        public ChatClient.ChatRequestBuilder option(String key, Object value) {
            this.options.put(key, value);
            return this;
        }

        @Override
        public Flux<ModelResponse> stream() {
            ChatRequest request = buildRequest();
            RoutingDecision decision = facade.route(request, null);
            ChatModel model = facade.resolveModel(decision);
            return model.stream(request);
        }

        @Override
        public ModelResponse call() {
            ChatRequest request = buildRequest();
            RoutingDecision decision = facade.route(request, null);
            ChatModel model = facade.resolveModel(decision);
            return model.call(request);
        }

        private ChatRequest buildRequest() {
            return ChatRequest.builder()
                    .messages(messages)
                    .systemPrompt(systemPrompt)
                    .tools(tools)
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .thinkingEnabled(thinking)
                    .stream(true)
                    .extras(options)
                    .build();
        }
    }
}
