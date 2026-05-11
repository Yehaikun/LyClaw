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
 * ChatFacade 默认实现，聚合 ModelRouter、ChatModelRegistry 和 ChatClient。
 *
 * <p>route() 调用当前路由策略，chat() 先路由再调用模型。
 * ChatClient Builder 支持链式调用，内部聚合请求参数后委托给 resolveModel+stream/call。
 */
public class DefaultChatFacade implements ChatFacade {

    private static final Logger log = LoggerFactory.getLogger(DefaultChatFacade.class);

    private final ChatModelRegistry registry;
    private final ModelRouter router;
    private final ChatClient defaultClient;

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
