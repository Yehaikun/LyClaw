package lyjew.com.lyclaw.autoconfigure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LyClaw配置属性绑定器。
 *
 * <p>Spring配置类，负责将{@link LyClawConfigurationProperties}作为Spring Bean
 * 注册到容器中，使用户可以通过{@code @Autowired}直接注入配置属性对象。
 * 通过{@code @EnableConfigurationProperties}启用Spring Boot的属性绑定机制，
 * 自动将application.yml中前缀为{@code lyclaw}的配置映射到属性对象中。</p>
 *
 * <p>该Bean的注册条件是应用上下文中不存在名为"lyClawProperties"的Bean时才会创建，
 * 允许用户覆盖默认配置。当Bean存在时，优先使用用户自定义的配置。</p>
 *
 * @author lyjew
 */
@Configuration
@EnableConfigurationProperties(LyClawConfigurationProperties.class)
public class LyClawPropertiesBinder {

    /**
     * 注册LyClaw配置属性Bean，暴露给应用上下文使用。
     *
     * <p>仅在上下文中不存在同名Bean时创建（{@code @ConditionalOnMissingBean}），
     * 用户可通过定义自己的"lyClawProperties" Bean来覆盖此默认配置。</p>
     *
     * @param props 由Spring Boot自动绑定的LyClawConfigurationProperties实例
     * @return 配置属性实例
     */
    @Bean
    @ConditionalOnMissingBean(name = "lyClawProperties")
    public LyClawConfigurationProperties lyClawProperties(LyClawConfigurationProperties props) {
        return props;
    }
}
