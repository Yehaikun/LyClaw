package lyjew.com.lyclaw.gateway;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

/**
 * 网关配置类，定义路由规则和CORS跨域配置。
 * <p>
 * 路由规则将不同API路径前缀的请求通过负载均衡(lb://)转发到对应的微服务。
 * CORS配置允许所有来源和方法，适用于开发环境。
 * </p>
 */
@Configuration
public class GatewayConfig {

    /**
     * 自定义路由配置：将API路径映射到对应的微服务。
     *
     * @param builder 路由构建器
     * @return 路由定位器
     */
    @Bean
    public RouteLocator customRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("chat-api", r -> r
                        .path("/api/chat", "/api/chat/stream")
                        .uri("lb://lyclaw-orchestration-service"))
                .route("sessions-api", r -> r
                        .path("/api/sessions/**")
                        .uri("lb://lyclaw-orchestration-service"))
                .route("orchestration-api", r -> r
                        .path("/api/orchestration/**")
                        .uri("lb://lyclaw-orchestration-service"))
                .route("memory-api", r -> r
                        .path("/api/memory/**")
                        .uri("lb://lyclaw-memory-service"))
                .route("plan-api", r -> r
                        .path("/api/plan/**")
                        .uri("lb://lyclaw-plan-service"))
                .route("action-api", r -> r
                        .path("/api/action/**")
                        .uri("lb://lyclaw-action-service"))
                .route("reflect-api", r -> r
                        .path("/api/reflect/**")
                        .uri("lb://lyclaw-reflect-service"))
                .route("protocol-api", r -> r
                        .path("/api/protocol/**")
                        .uri("lb://lyclaw-protocol-service"))
                .build();
    }

    /**
     * CORS跨域过滤器配置：允许所有来源和方法，允许携带凭证，缓存1小时。
     *
     * @return CORS Web过滤器
     */
    @Bean
    public CorsWebFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of(
                HttpMethod.GET.name(),
                HttpMethod.POST.name(),
                HttpMethod.PUT.name(),
                HttpMethod.DELETE.name(),
                HttpMethod.OPTIONS.name()));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
