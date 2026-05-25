package lyjew.com.lyclaw.autoconfigure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * LyClaw配置属性绑定器。
 *
 * <p>Spring配置类，通过{@code @EnableConfigurationProperties}启用Spring Boot的属性绑定机制，
 * 自动将application.yml中前缀为{@code lyclaw}的配置映射到{@link LyClawConfigurationProperties}对象中。
 * 该Bean注册到容器后，其他组件可通过{@code @Autowired}直接注入配置属性对象。</p>
 *
 * @author lyjew
 */
@Configuration
@EnableConfigurationProperties(LyClawConfigurationProperties.class)
public class LyClawPropertiesBinder {
}
