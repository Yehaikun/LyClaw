package lyjew.com.lyclaw.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 引擎自动装配类 —— Spring Boot 启动时自动配置 engine 层所有 Bean。
 *
 * <p>通过 @ComponentScan 扫描 lyjew.com.lyclaw 包下所有 @Component 类。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
@Configuration
@ComponentScan(basePackages = "lyjew.com.lyclaw")
public class EngineAutoConfiguration {

    private final EngineProperties properties;

    public EngineAutoConfiguration(EngineProperties properties) {
        this.properties = properties;
    }
}