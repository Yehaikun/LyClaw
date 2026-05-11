package lyjew.com.lyclaw.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class GatewayMetricsFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayMetricsFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.currentTimeMillis();
        String traceId = exchange.getAttribute("X-Trace-Id");
        if (traceId == null) {
            traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        }
        if (traceId == null) {
            traceId = "unknown";
        }
        String method = exchange.getRequest().getMethod() != null
                ? exchange.getRequest().getMethod().name()
                : "UNKNOWN";
        String path = exchange.getRequest().getURI().getPath();

        final String finalTraceId = traceId;

        return chain.filter(exchange).doFinally(signalType -> {
            long duration = System.currentTimeMillis() - startTime;
            HttpStatus status = HttpStatus.resolve(
                    exchange.getResponse().getStatusCode() != null
                            ? exchange.getResponse().getStatusCode().value()
                            : 500);
            int statusCode = status != null ? status.value() : 500;

            log.info("{{\"timestamp\":\"{}\",\"event\":\"request\",\"traceId\":\"{}\",\"method\":\"{}\",\"path\":\"{}\",\"status\":{},\"durationMs\":{}}}",
                    java.time.Instant.now().toString(),
                    finalTraceId,
                    method,
                    path,
                    statusCode,
                    duration);
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
