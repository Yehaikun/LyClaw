package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.react.DefaultReActEngine;
import lyjew.com.lyclaw.react.ReActEngine;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 交互模式自动配置，注册默认的 ReAct 引擎。
 *
 * <p>仅在 ChatFacade 可用时激活（有 LLM 调用能力才有 ReAct）。
 * 使用 {@code @ConditionalOnMissingBean} 允许用户通过声明自定义
 * ReActEngine 实现来替换默认的 ReAct 引擎。
 */
@AutoConfiguration
@ConditionalOnClass(ReActEngine.class)
public class InteractionModeAutoConfiguration {

    /**
     * 注册 DefaultReActEngine 作为默认的 ReAct 引擎 Bean。
     *
     * <p>仅在容器中不存在其他 ReActEngine 实现时才创建。
     * 用户可通过声明自己的 ReActEngine Bean（例如 CoT 引擎）来替换。
     *
     * @param chatFacade 聊天门面，用于 LLM 调用
     * @return DefaultReActEngine 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ChatFacade.class)
    public ReActEngine reActEngine(ChatFacade chatFacade) {
        return new DefaultReActEngine();
    }
}
