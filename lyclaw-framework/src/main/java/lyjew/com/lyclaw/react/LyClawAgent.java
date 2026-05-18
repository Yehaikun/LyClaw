package lyjew.com.lyclaw.react;

import java.util.List;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.tool.ToolRegistry;

/**
 * 声明式 Agent 的流畅构建器（Builder）。
 *
 * <p>用于在非 Spring 环境中手动创建 Agent 代理实例。在 Spring 环境中，推荐使用
 * {@code @Agent} 注解 + 自动注入，无需使用此类。
 *
 * <h3>基本用法</h3>
 * <pre>
 * MyAgent agent = LyClawAgent.builder(MyAgent.class)
 *         .chatFacade(chatFacade)
 *         .reActEngine(reActEngine)
 *         .tools(toolRegistry)
 *         .systemPrompt("You are a helpful assistant.")
 *         .build();
 *
 * String reply = agent.chat("Hello!");
 * </pre>
 *
 * <h3>高级用法</h3>
 * <pre>
 * MyAgent agent = LyClawAgent.builder(MyAgent.class)
 *         .chatFacade(chatFacade)
 *         .reActEngine(reActEngine)
 *         .tools(toolRegistry)
 *         .model("deepseek-v4-flash")
 *         .provider("deepseek")
 *         .build();
 *
 * Flux&lt;String&gt; stream = agent.chatStream("Tell me a story");
 * </pre>
 *
 * @param <T> Agent 接口类型
 */
public final class LyClawAgent {

    private LyClawAgent() {
    }

    /**
     * 为指定的 Agent 接口创建一个 Builder。
     *
     * @param agentInterface Agent 接口类
     * @param <T>            接口类型
     * @return Builder 实例
     */
    public static <T> Builder<T> builder(Class<T> agentInterface) {
        return new Builder<>(agentInterface);
    }

    public static class Builder<T> {
        private final Class<T> agentInterface;
        private ChatFacade chatFacade;
        private ReActEngine reActEngine;
        private ToolRegistry toolRegistry;
        private String systemPrompt;
        private String model;
        private String provider;
        private List<AgentHook> hooks;

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

        /**
         * 构建并返回代理实例。
         *
         * @return 代理实例
         * @throws IllegalStateException 如果必需依赖缺失
         */
        public T build() {
            AgentProxyFactory factory = new AgentProxyFactory(
                    chatFacade, reActEngine, toolRegistry,
                    systemPrompt, model, provider, hooks);
            return factory.create(agentInterface);
        }
    }
}
