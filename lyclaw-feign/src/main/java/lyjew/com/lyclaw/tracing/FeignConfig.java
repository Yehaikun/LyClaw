package lyjew.com.lyclaw.tracing;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign客户端全局配置类。
 *
 * <p>Spring配置类，负责注册Feign相关的Bean组件。
 * 主要作用是向Spring容器注册{@link TraceFeignInterceptor}拦截器Bean，
 * 使其自动应用于所有Feign客户端的HTTP请求，实现统一的分布式链路追踪。</p>
 *
 * <p>该类被各个Feign客户端接口（如{@code ProtocolFeignClient}等）
 * 通过{@code @FeignClient(configuration = FeignConfig.class)}引用，
 * 确保所有微服务间的HTTP调用都携带追踪信息。</p>
 *
 * @author lyjew
 */
@Configuration
public class FeignConfig {

    /**
     * 注册链路追踪拦截器Bean。
     *
     * <p>该Bean会被Spring Cloud Feign自动检测并应用于所有Feign请求，
     * 每次HTTP调用前自动注入X-Trace-Id和X-Span-Id请求头。</p>
     *
     * @return TraceFeignInterceptor实例
     */
    @Bean
    public RequestInterceptor traceFeignInterceptor() {
        return new TraceFeignInterceptor();
    }
}
