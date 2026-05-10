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

@Configuration
public class GatewayConfig {

    @Bean
    public RouteLocator customRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("chat-api", r -> r
                        .path("/api/chat", "/api/chat/stream")
                        .uri("lb://lyclaw-orchestration-service"))
                .route("sessions-api", r -> r
                        .path("/api/sessions/**")
                        .uri("lb://lyclaw-orchestration-service"))
                .route("memory-api", r -> r
                        .path("/api/memory/**")
                        .uri("lb://lyclaw-memory-service"))
                .route("plan-api", r -> r
                        .path("/api/plan/**")
                        .uri("lb://lyclaw-plan-service"))
                .route("action-api", r -> r
                        .path("/api/action/**", "/api/tools/**", "/api/skills/**")
                        .uri("lb://lyclaw-action-service"))
                .route("reflect-api", r -> r
                        .path("/api/reflect/**")
                        .uri("lb://lyclaw-reflect-service"))
                .route("protocol-api", r -> r
                        .path("/api/protocol/**", "/api/models/**")
                        .uri("lb://lyclaw-protocol-service"))
                .build();
    }

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
