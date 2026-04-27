package lyjew.com.lyclaw.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lyjew.com.lyclaw.client.ModelApiClient;
import lyjew.com.lyclaw.client.ClientImpl.OkHttpModelApiClient;
import lyjew.com.lyclaw.parser.ParserImpl.AnthropicResponseParser;
import lyjew.com.lyclaw.parser.ParserImpl.OpenAIResponseParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 模型抽象层自动配置类
 *
 * 确保 lyclaw-adapter 模块中的组件能被 Spring 扫描到，
 * 并声明模块所需的 Bean。
 *
 * 如果宿主应用已经提供了同类型的 Bean（如自定义的 ObjectMapper），
 * 则不会覆盖（@ConditionalOnMissingBean）。
 */
@Configuration
@ComponentScan(basePackages = {
        "lyjew.com.lyclaw.adapter",
        "lyjew.com.lyclaw.client",
        "lyjew.com.lyclaw.parser"
})
public class AdapterAutoConfiguration {

    /**
     * 提供默认的 ObjectMapper（如果宿主应用没有提供）
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // ★ 关键修复：注册 Java 8 时间模块
        mapper.registerModule(new JavaTimeModule());
        // 禁用将日期序列化为时间戳
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * 提供默认的 HTTP 客户端（如果宿主应用没有提供）
     */
    @Bean
    @ConditionalOnMissingBean(ModelApiClient.class)
    public ModelApiClient modelApiClient() {
        return new OkHttpModelApiClient();
    }

    /**
     * Anthropic 格式响应解析器
     */
    @Bean
    @ConditionalOnMissingBean(AnthropicResponseParser.class)
    public AnthropicResponseParser anthropicResponseParser() {
        return new AnthropicResponseParser();
    }

    /**
     * OpenAI 格式响应解析器
     */
    @Bean
    @ConditionalOnMissingBean(OpenAIResponseParser.class)
    public OpenAIResponseParser openAIResponseParser() {
        return new OpenAIResponseParser();
    }
}