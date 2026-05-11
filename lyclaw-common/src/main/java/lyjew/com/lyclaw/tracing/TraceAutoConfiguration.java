package lyjew.com.lyclaw.tracing;

import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

@Configuration
public class TraceAutoConfiguration {

    static {
        Hooks.enableAutomaticContextPropagation();
    }
}
