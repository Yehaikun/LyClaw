package lyjew.com.lyclaw.orchestration.config;

import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.util.List;

/**
 * Feign 客户端在 WebFlux（响应式）环境下的 HTTP 消息转换器配置。
 *
 * 在 Spring WebFlux 环境中，Feign 默认不会自动装配 HttpMessageConverters，
 * 因此需要手动注册一个包含 Jackson JSON 转换器的 Bean，确保 Feign 能
 * 正确序列化/反序列化请求与响应的 JSON 数据。
 */
@Configuration
public class FeignReactiveConfig {

    /**
     * 提供 Feign 所需的 HTTP 消息转换器。
     * 当前仅包含 MappingJackson2HttpMessageConverter，用于 JSON 序列化。
     *
     * @return HttpMessageConverters 实例
     */
    @Bean
    public HttpMessageConverters httpMessageConverters() {
        List<HttpMessageConverter<?>> converters = List.of(new MappingJackson2HttpMessageConverter());
        return new HttpMessageConverters(converters);
    }
}
