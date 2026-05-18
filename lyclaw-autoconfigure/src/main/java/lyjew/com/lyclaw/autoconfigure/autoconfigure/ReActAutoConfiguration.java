package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import lyjew.com.lyclaw.react.ApprovalStore;
import lyjew.com.lyclaw.react.DefaultReActEngine;
import lyjew.com.lyclaw.react.ReActEngine;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * ReAct 引擎自动配置，注册 ApprovalStore 和 DefaultReActEngine。
 */
@AutoConfiguration
@ConditionalOnClass(ReActEngine.class)
public class ReActAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DefaultReActEngine defaultReActEngine(ApprovalStore approvalStore) {
        return new DefaultReActEngine(approvalStore);
    }
}
