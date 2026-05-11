package lyjew.com.lyclaw.resilience;

/**
 * 弹性容错配置（待启用）。
 *
 * <p>预留 resilience4j 集成点，包含熔断器（CircuitBreaker）和重试（Retry）注册表。
 * TODO: 集成 resilience4j 依赖后取消注释并启用。</p>
 */
// TODO: Enable when resilience4j is integrated
// import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
// import io.github.resilience4j.retry.RetryRegistry;
// import org.springframework.context.annotation.Bean;
// import org.springframework.context.annotation.Configuration;

// @Configuration
public class ResilienceConfig {

    // @Bean
    // public CircuitBreakerRegistry circuitBreakerRegistry() {
    //     return CircuitBreakerRegistry.ofDefaults();
    // }

    // @Bean
    // public RetryRegistry retryRegistry() {
    //     return RetryRegistry.ofDefaults();
    // }
}
