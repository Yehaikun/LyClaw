package lyjew.com.lyclaw.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lyjew.com.lyclaw.session.MessageStore;
import lyjew.com.lyclaw.session.SessionStore;
import lyjew.com.lyclaw.web.persistence.SqliteMessageStore;
import lyjew.com.lyclaw.web.persistence.SqliteSessionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Web 层持久化配置。
 *
 * <p>提供 SQLite 版本的 {@link SessionStore} 和 {@link MessageStore}，
 * 覆盖框架 starter 中的默认内存实现。数据持久化到 SQLite 文件。</p>
 */
@Configuration
public class PersistenceConfiguration {

    @Bean
    public SessionStore sqliteSessionStore(
            @Value("${lyclaw.web.persistence.sqlite-path:./data/lyclaw-sessions.db}") String sqlitePath,
            ObjectMapper objectMapper) {
        return new SqliteSessionStore(Path.of(sqlitePath), objectMapper);
    }

    @Bean
    public MessageStore sqliteMessageStore(
            @Value("${lyclaw.web.persistence.sqlite-path:./data/lyclaw-sessions.db}") String sqlitePath,
            ObjectMapper objectMapper) {
        String jdbcUrl = "jdbc:sqlite:" + Path.of(sqlitePath).toAbsolutePath();
        return new SqliteMessageStore(jdbcUrl, objectMapper);
    }
}
