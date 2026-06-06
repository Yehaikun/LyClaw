package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import java.util.List;

import lyjew.com.lyclaw.config.AgentProperties;
import lyjew.com.lyclaw.react.AgentHook;
import lyjew.com.lyclaw.react.DefaultReActEngine;
import lyjew.com.lyclaw.react.HookRegistry;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.session.ContextPolicy;
import lyjew.com.lyclaw.session.DefaultSessionService;
import lyjew.com.lyclaw.session.ImmediateWritePolicy;
import lyjew.com.lyclaw.session.InMemoryMessageStore;
import lyjew.com.lyclaw.session.InMemorySessionStore;
import lyjew.com.lyclaw.session.InMemoryVariableStore;
import lyjew.com.lyclaw.session.MessageStore;
import lyjew.com.lyclaw.session.SessionService;
import lyjew.com.lyclaw.session.SessionStore;
import lyjew.com.lyclaw.session.SessionWritePolicy;
import lyjew.com.lyclaw.session.SlidingWindowPolicy;
import lyjew.com.lyclaw.session.VariableStore;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * ReAct 引擎自动配置。
 */
@AutoConfiguration
@ConditionalOnClass(ReActEngine.class)
public class ReActAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConfigurationProperties(prefix = "lyclaw.agent")
    public AgentProperties agentProperties() {
        return new AgentProperties();
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultReActEngine defaultReActEngine(AgentProperties agentProperties) {
        return new DefaultReActEngine(agentProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public HookRegistry hookRegistry(List<AgentHook> hooks) {
        return new HookRegistry(hooks);
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionStore sessionStore() {
        return new InMemorySessionStore();
    }

    // ── 新版 Session SPI Beans ──

    @Bean
    @ConditionalOnMissingBean
    public MessageStore messageStore() {
        return new InMemoryMessageStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public VariableStore variableStore() {
        return new InMemoryVariableStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionWritePolicy sessionWritePolicy() {
        return new ImmediateWritePolicy();
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextPolicy defaultContextPolicy() {
        return new SlidingWindowPolicy(50);
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionService sessionService(SessionStore sessionStore,
                                          MessageStore messageStore,
                                          VariableStore variableStore,
                                          SessionWritePolicy sessionWritePolicy,
                                          ContextPolicy defaultContextPolicy) {
        return new DefaultSessionService(sessionStore, messageStore, variableStore,
                sessionWritePolicy, defaultContextPolicy);
    }
}
