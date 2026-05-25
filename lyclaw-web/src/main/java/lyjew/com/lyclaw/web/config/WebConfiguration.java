package lyjew.com.lyclaw.web.config;

import lyjew.com.lyclaw.web.session.SessionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * lyclaw-web 最小配置——仅提供纯内存 SessionManager Bean。
 */
@Configuration
public class WebConfiguration {

    @Bean
    public SessionManager sessionManager() {
        return new SessionManager();
    }
}
