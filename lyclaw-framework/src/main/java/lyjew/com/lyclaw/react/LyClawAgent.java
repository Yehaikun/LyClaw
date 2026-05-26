package lyjew.com.lyclaw.react;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import lyjew.com.lyclaw.adapter.DeepSeekChatModel;
import lyjew.com.lyclaw.adapter.OpenAiProtocolChatModel;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.chat.ChatModel;
import lyjew.com.lyclaw.chat.ChatModelMetadata;
import lyjew.com.lyclaw.chat.DefaultChatFacade;
import lyjew.com.lyclaw.chat.DefaultChatModelRegistry;
import lyjew.com.lyclaw.chat.FirstAvailableRouter;
import lyjew.com.lyclaw.decorator.CircuitBreakerChatModel;
import lyjew.com.lyclaw.decorator.RetryChatModel;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelConfig;
import lyjew.com.lyclaw.model.ModelResponse;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.annotation.chat.RetryPolicy;

/**
 * 声明式 Agent 的流畅构建器（Builder）&amp; 独立模式入口。
 *
 * <h3>方式一：Agent 代理模式</h3>
 * <p>用于在非 Spring 环境中手动创建 Agent 代理实例。在 Spring 环境中，推荐使用
 * {@code @Agent} 注解 + 自动注入，无需使用此类。
 * <pre>
 * MyAgent agent = LyClawAgent.builder(MyAgent.class)
 *         .chatFacade(chatFacade).reActEngine(reActEngine).tools(toolRegistry)
 *         .systemPrompt("You are a helpful assistant.").build();
 * String reply = agent.chat("Hello!");
 * </pre>
 *
 * <h3>方式二：独立模式（无需 Spring）</h3>
 * <pre>
 * LyClawAgent agent = LyClawAgent.configure()
 *         .model("deepseek-chat", "sk-xxx")
 *         .maxRetries(3).build();
 * String reply = agent.chat("你好");
 * </pre>
 */
public final class LyClawAgent {

    // ── 独立模式字段 ──
    private final ChatFacade chatFacade;
    private final ToolRegistry toolRegistry;

    // ── 构造方法 ──

    private LyClawAgent() {
        this.chatFacade = null;
        this.toolRegistry = null;
    }

    LyClawAgent(ChatFacade chatFacade, ToolRegistry toolRegistry) {
        this.chatFacade = chatFacade;
        this.toolRegistry = toolRegistry;
    }

    // ── 静态工厂 ──

    /**
     * 为指定的 Agent 接口创建一个 Builder。
     */
    public static <T> Builder<T> builder(Class<T> agentInterface) {
        return new Builder<>(agentInterface);
    }

    /**
     * 创建独立模式构建器，无需 Spring 环境。
     */
    public static SimpleBuilder configure() {
        return new SimpleBuilder();
    }

    // ── 独立模式方法 ──

    public String chat(String userMessage) {
        return chat(userMessage, null);
    }

    public String chat(String userMessage, String systemPrompt) {
        ChatRequest request = new ChatRequest();
        request.setMessages(buildMessages(userMessage, systemPrompt));
        ModelResponse response = chatFacade.chat(request);
        return response != null && response.getContent() != null ? response.getContent() : "";
    }

    public void chatStream(String userMessage, Consumer<String> onChunk) {
        chatStream(userMessage, null, onChunk);
    }

    public void chatStream(String userMessage, String systemPrompt, Consumer<String> onChunk) {
        ChatRequest request = new ChatRequest();
        request.setMessages(buildMessages(userMessage, systemPrompt));
        request.setStream(true);
        chatFacade.chat(request);
    }

    public ChatFacade getChatFacade() { return chatFacade; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }

    // ── Builder（Agent 代理）──

    public static class Builder<T> {
        private final Class<T> agentInterface;
        private ChatFacade chatFacade;
        private ReActEngine reActEngine;
        private ToolRegistry toolRegistry;
        private String systemPrompt;
        private String model;
        private String provider;
        private List<AgentHook> hooks;
        private List<ReactivePipelineStage> stages;

        private Builder(Class<T> agentInterface) {
            this.agentInterface = agentInterface;
        }

        public Builder<T> chatFacade(ChatFacade chatFacade) {
            this.chatFacade = chatFacade;
            return this;
        }

        public Builder<T> reActEngine(ReActEngine reActEngine) {
            this.reActEngine = reActEngine;
            return this;
        }

        public Builder<T> tools(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry;
            return this;
        }

        public Builder<T> systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder<T> model(String model) {
            this.model = model;
            return this;
        }

        public Builder<T> provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder<T> hooks(List<AgentHook> hooks) {
            this.hooks = hooks;
            return this;
        }

        public Builder<T> stages(List<ReactivePipelineStage> stages) {
            this.stages = stages;
            return this;
        }

        public T build() {
            AgentProxyFactory factory = new AgentProxyFactory(
                    chatFacade, reActEngine, toolRegistry,
                    systemPrompt, model, provider, hooks, stages);
            return factory.create(agentInterface);
        }
    }

    // ── SimpleBuilder（独立模式）──

    public static class SimpleBuilder {
        private String provider = "deepseek";
        private String apiKey;
        private String model = "deepseek-v4-flash";
        private String baseUrl;
        private int maxRetries = 3;
        private int circuitBreakerThreshold = 5;
        private long circuitBreakerTimeoutMs = 30000;
        private ToolRegistry toolRegistry;

        SimpleBuilder() {}

        public SimpleBuilder provider(String provider) { this.provider = provider; return this; }
        public SimpleBuilder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public SimpleBuilder model(String model) { this.model = model; return this; }
        public SimpleBuilder model(String model, String apiKey) {
            this.model = model;
            this.apiKey = apiKey;
            if (model != null && model.startsWith("deepseek")) this.provider = "deepseek";
            return this;
        }
        public SimpleBuilder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        public SimpleBuilder maxRetries(int maxRetries) { this.maxRetries = maxRetries; return this; }
        public SimpleBuilder circuitBreakerThreshold(int threshold) { this.circuitBreakerThreshold = threshold; return this; }
        public SimpleBuilder circuitBreakerTimeoutMs(long timeoutMs) { this.circuitBreakerTimeoutMs = timeoutMs; return this; }
        public SimpleBuilder toolRegistry(ToolRegistry toolRegistry) { this.toolRegistry = toolRegistry; return this; }

        public LyClawAgent build() {
            ModelConfig config = new ModelConfig();
            config.setProvider(provider);
            config.setModel(model);
            config.setApiKey(apiKey);
            if (baseUrl != null) config.setBaseUrl(baseUrl);

            ChatModel chatModel = createChatModel(config);

            if (maxRetries > 0) {
                chatModel = new RetryChatModel(chatModel, maxRetries, 1000L,
                        RetryPolicy.BackoffStrategy.EXPONENTIAL, 0.3);
            }
            if (circuitBreakerThreshold > 0) {
                chatModel = new CircuitBreakerChatModel(chatModel, circuitBreakerThreshold, circuitBreakerTimeoutMs);
            }

            DefaultChatModelRegistry registry = new DefaultChatModelRegistry();
            String modelName = chatModel.model();
            registry.register(provider, modelName, chatModel,
                    new ChatModelMetadata(provider, provider, "", null,
                            chatModel.capabilities(), modelName, baseUrl, "1.0", 0));

            FirstAvailableRouter router = new FirstAvailableRouter(registry);
            DefaultChatFacade facade = new DefaultChatFacade(registry, router);

            return new LyClawAgent(facade, toolRegistry);
        }

        private ChatModel createChatModel(ModelConfig config) {
            return switch (provider.toLowerCase()) {
                case "deepseek" -> new DeepSeekChatModel(config);
                default -> new OpenAiProtocolChatModel(provider, config);
            };
        }
    }

    // ── 内部辅助 ──

    private static List<Message> buildMessages(String userMessage, String systemPrompt) {
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            Message sysMsg = new Message();
            sysMsg.setRole("system");
            sysMsg.setContent(systemPrompt);
            messages.add(sysMsg);
        }
        Message userMsg = new Message();
        userMsg.setRole("user");
        userMsg.setContent(userMessage);
        messages.add(userMsg);
        return messages;
    }
}
