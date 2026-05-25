package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import lyjew.com.lyclaw.config.AgentProperties;
import lyjew.com.lyclaw.persistence.repository.ApprovalRepository;
import lyjew.com.lyclaw.persistence.sqlite.SqliteConfig;
import lyjew.com.lyclaw.persistence.sqlite.SqliteConnectionManager;
import lyjew.com.lyclaw.persistence.sqlite.SqliteMigrationService;
import lyjew.com.lyclaw.react.ApprovalStore;
import lyjew.com.lyclaw.react.DefaultReActEngine;
import lyjew.com.lyclaw.react.ReActEngine;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * ReAct 引擎自动配置，注册 ApprovalStore 和 DefaultReActEngine。
 *
 * <p>同时也创建 {@link SqliteConnectionManager}、{@link ApprovalRepository}
 * 和 {@link ApprovalStore} 等持久化 Bean，使得框架在未引入 lyclaw-web 时
 * 也能正常工作。所有 Bean 均为 {@code @ConditionalOnMissingBean}，因此
 * lyclaw-web 中的 {@code StorageAutoConfiguration} 可以覆盖它们。
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
    public SqliteConnectionManager sqliteConnectionManager(
            @Value("${lyclaw.storage.base-path:${user.dir}/lyclaw-data}") String basePath) {
        SqliteConfig config = SqliteConfig.builder()
                .dbPath(basePath + "/index/lyclaw.db")
                .build();
        return new SqliteConnectionManager(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public SqliteMigrationService sqliteMigrationService(SqliteConnectionManager cm) {
        SqliteMigrationService service = new SqliteMigrationService(cm);
        service.migrate();
        return service;
    }

    @Bean
    @ConditionalOnMissingBean
    public ApprovalRepository approvalRepository(SqliteConnectionManager cm) {
        return new ApprovalRepository(cm);
    }

    @Bean
    @ConditionalOnMissingBean
    public ApprovalStore approvalStore(AgentProperties agentProperties,
                                        ApprovalRepository approvalRepo) {
        return new ApprovalStore(agentProperties, approvalRepo);
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultReActEngine defaultReActEngine(ApprovalStore approvalStore, AgentProperties agentProperties) {
        return new DefaultReActEngine(approvalStore, agentProperties);
    }
}
