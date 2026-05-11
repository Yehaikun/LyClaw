package lyjew.com.lyclaw.tracing;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignConfig {

    @Bean
    public RequestInterceptor traceFeignInterceptor() {
        return new TraceFeignInterceptor();
    }
}
