package lyjew.com.lyclaw.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 共享 ObjectMapper 配置 — 替代各处散落的 {@code new ObjectMapper()}。
 *
 * <p>所有需要 JSON 序列化的组件通过构造器注入此 Bean，
 * 确保统一配置（缩进、日期格式、未知属性策略等）。
 */
@Configuration
public class ObjectMapperConfig {

    @Bean
    public ObjectMapper sharedObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
