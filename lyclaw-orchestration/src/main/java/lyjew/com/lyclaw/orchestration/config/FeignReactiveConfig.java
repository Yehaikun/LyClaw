package lyjew.com.lyclaw.orchestration.config;

import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.util.List;

@Configuration
public class FeignReactiveConfig {

    @Bean
    public HttpMessageConverters httpMessageConverters() {
        List<HttpMessageConverter<?>> converters = List.of(new MappingJackson2HttpMessageConverter());
        return new HttpMessageConverters(converters);
    }
}
