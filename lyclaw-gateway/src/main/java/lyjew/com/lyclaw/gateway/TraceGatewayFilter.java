package lyjew.com.lyclaw.gateway;

import lyjew.com.lyclaw.tracing.TraceConstants;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class TraceGatewayFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(TraceConstants.HEADER_TRACE_ID);
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        String spanId = exchange.getRequest().getHeaders().getFirst(TraceConstants.HEADER_SPAN_ID);
        if (spanId == null || spanId.isEmpty()) {
            spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        exchange.getResponse().getHeaders().add(TraceConstants.HEADER_TRACE_ID, traceId);

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(TraceConstants.HEADER_TRACE_ID, traceId)
                .header(TraceConstants.HEADER_SPAN_ID, spanId)
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
