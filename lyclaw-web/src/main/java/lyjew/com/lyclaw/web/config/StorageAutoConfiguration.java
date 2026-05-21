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
import lyjew.com.lyclaw.web.session.AgentCleanupService;
import lyjew.com.lyclaw.web.session.SessionManager;
import org.springframework.beans.factory.annotation.Value;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    @Bean
    public AgentCleanupService agentCleanupService(AgentRepository agentRepo,
                                                    SessionRepository sessionRepo,
                                                    StorageProperties storageProperties) {
        return new AgentCleanupService(agentRepo, sessionRepo, storageProperties);
    }

    /**
     * 启动时验证存储层就绪——SQLite数据库可访问、基路径存在。
     * 不加载JSONL消息到内存，消息通过懒加载（SessionManager.getSession）按需读取。
     */
    @Bean
    public ApplicationRunner storageReadinessCheck(SqliteConnectionManager cm,
                                                   StorageProperties storageProperties) {
        Logger log = LoggerFactory.getLogger(StorageAutoConfiguration.class);
        return args -> {
            try {
                cm.getConnection().close();
                log.info("存储层就绪: SQLite可用, basePath={}", storageProperties.getBasePath());
            } catch (Exception e) {
                log.error("存储层启动失败: SQLite不可访问", e);
                throw new RuntimeException("存储层不可用", e);
            }
        };
    }

    /**
     * 幂等初始化默认chat Agent——首次启动时INSERT，后续启动跳过。
     * 确保前端AgentSelector默认选中的"chat"始终存在于SQLite中。
     */
    @Bean
    public ApplicationRunner defaultAgentInitializer(AgentRepository agentRepo,
                                                      StorageProperties storageProperties) {
        Logger log = LoggerFactory.getLogger(StorageAutoConfiguration.class);
        return args -> {
            if (agentRepo.findById("chat") != null) {
                log.debug("默认chat Agent已存在，跳过初始化");
                return;
            }
            log.info("首次启动——创建默认chat Agent...");
            long now = System.currentTimeMillis();
            String dirPath = storageProperties.getBasePath() + "/agents/chat";
            new File(dirPath).mkdirs();

            Map<String, Object> dbRow = new LinkedHashMap<>();
            dbRow.put("agent_id", "chat");
            dbRow.put("agent_name", "chat");
            dbRow.put("description", "通用聊天助手，具备工具调用能力");
            dbRow.put("lifecycle", "permanent");
            dbRow.put("created_by", "system");
            dbRow.put("parent_agent_id", null);
            dbRow.put("parent_session_id", null);
            dbRow.put("model", "deepseek-v4-pro");
            dbRow.put("provider", "deepseek");
            dbRow.put("thinking_level", "medium");
            dbRow.put("verbose_level", "low");
            dbRow.put("reasoning_level", "medium");
            dbRow.put("fast_mode", 0);
            dbRow.put("sandbox_level", "PROCESS");
            dbRow.put("skills", "[]");
            dbRow.put("allow_agents", "[\"*\"]");
            dbRow.put("max_spawn_depth", 1);
            dbRow.put("max_children", 5);
            dbRow.put("system_prompt", "");
            dbRow.put("soul_prompt", "");
            dbRow.put("identity_display_name", "Chat");
            dbRow.put("avatar_url", "");
            dbRow.put("avatar_file_path", "");
            dbRow.put("created_at", now);
            dbRow.put("directory_path", dirPath);
            agentRepo.insert(dbRow);
            log.info("默认chat Agent创建完成: dir={}", dirPath);
        };
    }
}
