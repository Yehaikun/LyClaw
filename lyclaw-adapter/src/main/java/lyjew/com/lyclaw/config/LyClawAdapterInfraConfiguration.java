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
 * LyClaw 适配器基础设施的 Spring 自动配置类。
 *
 * <p>负责扫描并注册 adapter、client、parser 三个包下的组件，
 * 同时提供默认的 Bean 定义：</p>
 * <ul>
 *   <li>{@link ObjectMapper} -- 配置了 JavaTimeModule，禁用日期时间戳序列化</li>
 *   <li>{@link ModelApiClient} -- 默认使用 OkHttp 实现</li>
 *   <li>{@link AnthropicResponseParser} -- Anthropic 格式响应解析器</li>
 *   <li>{@link OpenAIResponseParser} -- OpenAI 格式响应解析器</li>
 * </ul>
 *
 * <p>所有 Bean 都标注了 {@link ConditionalOnMissingBean}，
 * 允许用户自行覆盖任意默认实现。</p>
 */
@Configuration
@ComponentScan(basePackages = {
        "lyjew.com.lyclaw.adapter",
        "lyjew.com.lyclaw.client",
        "lyjew.com.lyclaw.parser"
})
public class LyClawAdapterInfraConfiguration {

    /**
     * 提供全局唯一的 ObjectMapper 实例。
     *
     * <p>注册了 Java 8 时间模块，并禁用了将日期序列化为时间戳的行为，
     * 以支持 LocalDateTime 等类型的 JSON 序列化。</p>
     *
     * @return 配置好的 ObjectMapper
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // 注册 Java 8 时间模块，支持 LocalDateTime 等类型
        mapper.registerModule(new JavaTimeModule());
        // 禁用日期序列化为时间戳，使用 ISO 格式字符串
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * 提供默认的 HTTP API 客户端实现。
     *
     * @return OkHttp 实现的 ModelApiClient
     */
    @Bean
    @ConditionalOnMissingBean(ModelApiClient.class)
    public ModelApiClient modelApiClient() {
        return new OkHttpModelApiClient();
    }

    /**
     * 提供 Anthropic 格式响应的解析器。
     *
     * @return AnthropicResponseParser 实例
     */
    @Bean
    @ConditionalOnMissingBean(AnthropicResponseParser.class)
    public AnthropicResponseParser anthropicResponseParser() {
        return new AnthropicResponseParser();
    }

    /**
     * 提供 OpenAI 格式响应的解析器。
     *
     * @return OpenAIResponseParser 实例
     */
    @Bean
    @ConditionalOnMissingBean(OpenAIResponseParser.class)
    public OpenAIResponseParser openAIResponseParser() {
        return new OpenAIResponseParser();
    }
}
