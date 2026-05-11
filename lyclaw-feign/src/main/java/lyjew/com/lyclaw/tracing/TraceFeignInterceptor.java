package lyjew.com.lyclaw.tracing;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;

import java.util.UUID;

import static lyjew.com.lyclaw.tracing.TraceConstants.HEADER_SPAN_ID;
import static lyjew.com.lyclaw.tracing.TraceConstants.HEADER_TRACE_ID;
import static lyjew.com.lyclaw.tracing.TraceConstants.MDC_TRACE_ID;

/**
 * Feign分布式链路追踪拦截器。
 *
 * <p>实现{@link RequestInterceptor}接口，在每个Feign HTTP请求发出前自动注入
 * 分布式追踪信息。通过向请求头添加"X-Trace-Id"（全链路唯一标识）和
 * "X-Span-Id"（当前调用段标识）来实现分布式调用链的追踪。</p>
 *
 * <p>优先从{@link MDC}中获取已有的traceId以保持调用链连续性；
 * 若MDC中无traceId，则生成新的UUID作为traceId。
 * 每次调用均生成新的spanId表示当前这次远程调用。</p>
 *
 * <p>典型数据流：请求进入 → MDC设置traceId → Feign调用 →
 * 拦截器注入traceId/spanId → 下游服务接收追踪信息</p>
 *
 * @author lyjew
 */
public class TraceFeignInterceptor implements RequestInterceptor {

    /**
     * 在Feign请求发送前向请求头注入追踪信息。
     *
     * <p>处理逻辑：
     * <ol>
     *   <li>从MDC中尝试获取当前traceId（保持链路连续性）</li>
     *   <li>若无traceId则生成新的（32位UUID，无横线）</li>
     *   <li>生成新的spanId（16位UUID前缀）标识本次远程调用</li>
     *   <li>将traceId和spanId注入HTTP请求头</li>
     * </ol>
     * </p>
     *
     * @param template Feign请求模板，用于设置请求头
     */
    @Override
    public void apply(RequestTemplate template) {
        // 优先从MDC获取已有traceId，保持调用链连续性
        String traceId = MDC.get(MDC_TRACE_ID);
        if (traceId == null || traceId.isEmpty()) {
            // 首次进入时生成新的traceId（32位UUID，无横线）
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        // 每次调用生成新的spanId标识当前调用段（16位UUID前缀）
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        template.header(HEADER_TRACE_ID, traceId);
        template.header(HEADER_SPAN_ID, spanId);
    }
}
