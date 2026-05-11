package lyjew.com.lyclaw.tracing;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(TraceConstants.HEADER_TRACE_ID);
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        String parentSpanId = exchange.getRequest().getHeaders().getFirst(TraceConstants.HEADER_SPAN_ID);
        String spanId = UUID.randomUUID().toString().replace("-", "");

        MDC.put(TraceConstants.MDC_TRACE_ID, traceId);
        MDC.put(TraceConstants.MDC_SPAN_ID, spanId);
        if (parentSpanId != null && !parentSpanId.isEmpty()) {
            MDC.put("parentSpanId", parentSpanId);
        }

        exchange.getResponse().getHeaders().add(TraceConstants.HEADER_TRACE_ID, traceId);

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(TraceConstants.HEADER_TRACE_ID, traceId)
                .header(TraceConstants.HEADER_SPAN_ID, spanId)
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .doFinally(signalType -> {
                    MDC.remove(TraceConstants.MDC_TRACE_ID);
                    MDC.remove(TraceConstants.MDC_SPAN_ID);
                    MDC.remove("parentSpanId");
                });
    }
}
