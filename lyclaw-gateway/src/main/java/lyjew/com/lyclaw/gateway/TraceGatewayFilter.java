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

/**
 * 链路追踪全局过滤器，为每个进入网关的请求注入或传递traceId和spanId。
 * <p>
 * 如果请求头中已存在traceId则透传，否则生成新的UUID作为traceId。
 * 该过滤器具有最高优先级(HIGHEST_PRECEDENCE)，确保在所有其他过滤器之前执行。
 * 响应头中会回写traceId，方便客户端追踪。
 * </p>
 */
@Component
public class TraceGatewayFilter implements GlobalFilter, Ordered {

    /**
     * 过滤逻辑：检查并补充traceId/spanId，然后传递给下游。
     *
     * @param exchange 当前请求的ServerWebExchange
     * @param chain    过滤器链
     * @return 链式调用的Mono
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 尝试从请求头获取traceId，若无则生成新的
        String traceId = exchange.getRequest().getHeaders().getFirst(TraceConstants.HEADER_TRACE_ID);
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        // 尝试从请求头获取spanId，若无则生成新的（取前16位）
        String spanId = exchange.getRequest().getHeaders().getFirst(TraceConstants.HEADER_SPAN_ID);
        if (spanId == null || spanId.isEmpty()) {
            spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        // 响应头中回写traceId
        exchange.getResponse().getHeaders().add(TraceConstants.HEADER_TRACE_ID, traceId);

        // 构建携带traceId/spanId的变异请求，传递给下游
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(TraceConstants.HEADER_TRACE_ID, traceId)
                .header(TraceConstants.HEADER_SPAN_ID, spanId)
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    /** @return 最高优先级，确保最先执行 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
