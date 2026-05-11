package lyjew.com.lyclaw.autoconfigure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LyClawConfigurationProperties.class)
public class LyClawPropertiesBinder {

    @Bean
    @ConditionalOnMissingBean(name = "lyClawProperties")
    public LyClawConfigurationProperties lyClawProperties(LyClawConfigurationProperties props) {
        return props;
    }
}
