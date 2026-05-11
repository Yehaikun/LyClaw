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

/**
 * 链路追踪 Web 过滤器，在请求链路入口处拦截并注入追踪信息。
 *
 * <p>该过滤器以最高优先级在每个 HTTP 请求到达时自动完成以下工作：
 * <ol>
 *   <li>从请求头中提取或生成 traceId 和 spanId</li>
 *   <li>将追踪信息写入 MDC 供后续日志使用</li>
 *   <li>将 traceId 写入响应头方便客户端追踪</li>
 *   <li>将追踪头注入下游请求</li>
 *   <li>请求结束后清理 MDC</li>
 * </ol>
 * <p>这是实现全链路追踪的关键入口组件。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 从请求头提取 traceId，若无则生成新的
        String traceId = exchange.getRequest().getHeaders().getFirst(TraceConstants.HEADER_TRACE_ID);
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        String parentSpanId = exchange.getRequest().getHeaders().getFirst(TraceConstants.HEADER_SPAN_ID);
        String spanId = UUID.randomUUID().toString().replace("-", "");

        // 将追踪信息写入 MDC，使日志自动携带 traceId 和 spanId
        MDC.put(TraceConstants.MDC_TRACE_ID, traceId);
        MDC.put(TraceConstants.MDC_SPAN_ID, spanId);
        if (parentSpanId != null && !parentSpanId.isEmpty()) {
            MDC.put("parentSpanId", parentSpanId);
        }

        // 将 traceId 写入响应头
        exchange.getResponse().getHeaders().add(TraceConstants.HEADER_TRACE_ID, traceId);

        // 将追踪信息注入下游请求的请求头
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(TraceConstants.HEADER_TRACE_ID, traceId)
                .header(TraceConstants.HEADER_SPAN_ID, spanId)
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .doFinally(signalType -> {
                    // 请求结束后清理 MDC，防止内存泄露
                    MDC.remove(TraceConstants.MDC_TRACE_ID);
                    MDC.remove(TraceConstants.MDC_SPAN_ID);
                    MDC.remove("parentSpanId");
                });
    }
}
