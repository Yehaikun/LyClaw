package lyjew.com.lyclaw.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lyjew.com.lyclaw.config.StorageProperties;
import lyjew.com.lyclaw.persistence.SessionFactory;
import lyjew.com.lyclaw.persistence.jsonl.DefaultJsonlReader;
import lyjew.com.lyclaw.persistence.jsonl.DefaultJsonlWriter;
import lyjew.com.lyclaw.persistence.jsonl.JsonlReader;
import lyjew.com.lyclaw.persistence.jsonl.JsonlWriter;
import lyjew.com.lyclaw.persistence.queue.AsyncWriteQueueRegistry;
import lyjew.com.lyclaw.persistence.repository.AgentRepository;
import lyjew.com.lyclaw.persistence.repository.ApprovalRepository;
import lyjew.com.lyclaw.persistence.repository.SessionRepository;
import lyjew.com.lyclaw.persistence.sqlite.SqliteConfig;
import lyjew.com.lyclaw.persistence.sqlite.SqliteConnectionManager;
import lyjew.com.lyclaw.persistence.sqlite.SqliteMigrationService;
import lyjew.com.lyclaw.react.ReActMessageHook;
import lyjew.com.lyclaw.web.session.SessionManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 新存储层自动配置——装配SQLite+JSONL双轨存储的所有Bean。
 *
 * 替代旧lyclaw-autoconfigure中的StorageAutoConfiguration（基于多后端抽象），
 * 新架构固定使用SQLite（元数据）+JSONL（消息转录），无后端切换。
 * 同时将SessionManager.onMessage注册为ReActMessageHook Bean，
 * 将SessionManager暴露为SessionFactory接口供framework层使用。
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageAutoConfiguration {

    @Bean
    public SqliteConnectionManager sqliteConnectionManager(
            @Value("${lyclaw.storage.base-path}") String basePath) {
        SqliteConfig config = SqliteConfig.builder()
                .dbPath(basePath + "/index/lyclaw.db")
                .build();
        return new SqliteConnectionManager(config);
    }

    @Bean
    public SqliteMigrationService sqliteMigrationService(SqliteConnectionManager cm) {
        SqliteMigrationService service = new SqliteMigrationService(cm);
        service.migrate();  // 启动时自动建表
        return service;
    }

    @Bean
    public JsonlWriter jsonlWriter(ObjectMapper objectMapper) {
        return new DefaultJsonlWriter(objectMapper);
    }

    @Bean
    public JsonlReader jsonlReader(ObjectMapper objectMapper) {
        return new DefaultJsonlReader(objectMapper);
    }

    @Bean
    public AgentRepository agentRepository(SqliteConnectionManager cm) {
        return new AgentRepository(cm);
    }

    @Bean
    public SessionRepository sessionRepository(SqliteConnectionManager cm,
                                                JsonlWriter writer, JsonlReader reader,
                                                StorageProperties storageProperties) {
        return new SessionRepository(cm, writer, reader, storageProperties);
    }

    @Bean
    public ApprovalRepository approvalRepository(SqliteConnectionManager cm) {
        return new ApprovalRepository(cm);
    }

    @Bean
    public AsyncWriteQueueRegistry asyncWriteQueueRegistry() {
        return new AsyncWriteQueueRegistry();
    }

    /**
     * 将SessionManager暴露为SessionFactory接口。
     * 供framework层的SubagentSpawner使用，解耦模块依赖。
     */
    @Bean
    public SessionFactory sessionFactory(SessionManager sessionManager) {
        return sessionManager;
    }

    /**
     * 将SessionManager.onMessage注册为ReActMessageHook。
     * Spring自动注入List&lt;ReActMessageHook&gt;到ToolCallLoop。
     * 方法引用签名匹配：void onMessage(Session, Message)
     */
    @Bean
    public ReActMessageHook persistenceHook(SessionManager sessionManager) {
        return sessionManager::onMessage;
    }

    @Bean
    public SessionManager sessionManager(SessionRepository sessionRepo,
                                          AsyncWriteQueueRegistry queueRegistry,
                                          StorageProperties storageProperties) {
        return new SessionManager(sessionRepo, queueRegistry, storageProperties);
    }
}
