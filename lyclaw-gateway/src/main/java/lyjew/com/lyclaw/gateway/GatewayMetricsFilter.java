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

/**
 * 网关请求指标过滤器，记录每个请求的方法、路径、状态码和耗时。
 * <p>
 * 在请求完成后（通过doFinally）以JSON格式输出请求指标日志，
 * 方便日志采集系统（如ELK/Loki）进行聚合分析。
 * 优先级在TraceGatewayFilter之后，确保traceId已经生成。
 * </p>
 */
@Component
public class GatewayMetricsFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayMetricsFilter.class);

    /**
     * 过滤逻辑：记录请求开始时间，请求完成后输出指标日志。
     *
     * @param exchange 当前请求的ServerWebExchange
     * @param chain    过滤器链
     * @return 链式调用的Mono，带doFinally回调
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.currentTimeMillis();
        // 从exchange属性或请求头获取traceId
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

        // doFinally确保无论成功或异常都会输出日志
        return chain.filter(exchange).doFinally(signalType -> {
            long duration = System.currentTimeMillis() - startTime;
            HttpStatus status = HttpStatus.resolve(
                    exchange.getResponse().getStatusCode() != null
                            ? exchange.getResponse().getStatusCode().value()
                            : 500);
            int statusCode = status != null ? status.value() : 500;

            // 以JSON格式输出结构化日志，方便日志采集
            log.info("{{\"timestamp\":\"{}\",\"event\":\"request\",\"traceId\":\"{}\",\"method\":\"{}\",\"path\":\"{}\",\"status\":{},\"durationMs\":{}}}",
                    java.time.Instant.now().toString(),
                    finalTraceId,
                    method,
                    path,
                    statusCode,
                    duration);
        });
    }

    /** @return 在TraceGatewayFilter之后执行 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
