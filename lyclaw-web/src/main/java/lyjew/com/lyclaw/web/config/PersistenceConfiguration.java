package lyjew.com.lyclaw.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lyjew.com.lyclaw.session.SessionStore;
import lyjew.com.lyclaw.web.persistence.SqliteSessionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Web 层持久化配置。
 *
 * <p>这里提供 SQLite 版本的 {@link SessionStore}，覆盖框架 starter 中的默认内存实现。</p>
 */
@Configuration
public class PersistenceConfiguration {

    @Bean
    public SessionStore sqliteSessionStore(
            @Value("${lyclaw.web.persistence.sqlite-path:./data/lyclaw-sessions.db}") String sqlitePath,
            ObjectMapper objectMapper) {
        return new SqliteSessionStore(Path.of(sqlitePath), objectMapper);
    }
}
