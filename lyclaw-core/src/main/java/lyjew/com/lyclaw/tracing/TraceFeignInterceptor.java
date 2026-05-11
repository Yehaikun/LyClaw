package lyjew.com.lyclaw.tracing;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;

import java.util.UUID;

public class TraceFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        template.header("X-Trace-Id", traceId);
        template.header("X-Span-Id", spanId);
    }
}
