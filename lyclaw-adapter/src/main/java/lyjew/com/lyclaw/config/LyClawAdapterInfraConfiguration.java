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

@Configuration
@ComponentScan(basePackages = {
        "lyjew.com.lyclaw.adapter",
        "lyjew.com.lyclaw.client",
        "lyjew.com.lyclaw.parser"
})
public class LyClawAdapterInfraConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Bean
    @ConditionalOnMissingBean(ModelApiClient.class)
    public ModelApiClient modelApiClient() {
        return new OkHttpModelApiClient();
    }

    @Bean
    @ConditionalOnMissingBean(AnthropicResponseParser.class)
    public AnthropicResponseParser anthropicResponseParser() {
        return new AnthropicResponseParser();
    }

    @Bean
    @ConditionalOnMissingBean(OpenAIResponseParser.class)
    public OpenAIResponseParser openAIResponseParser() {
        return new OpenAIResponseParser();
    }
}
