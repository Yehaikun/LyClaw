package lyjew.com.lyclaw.orchestration.web;

import lyjew.com.lyclaw.base.exception.LyClawException;
import org.slf4j.MDC;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.reactive.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局错误属性提供者，覆盖 Spring Boot 默认的错误属性构建逻辑。
 *
 * <p>职责：为 GlobalErrorWebExceptionHandler 提供结构化错误信息。
 * 扩展了 {@link DefaultErrorAttributes}，在错误响应中加入分布式追踪所需的字段：
 * <ul>
 *   <li><b>traceId / spanId</b>：从 SLF4J MDC 中提取的分布式追踪标识，便于跨服务日志关联。</li>
 *   <li><b>service</b>：标识当前服务的名称，在微服务架构中便于定位问题来源。</li>
 * </ul>
 *
 * <p>对于自定义的 LyClawException 异常，会从异常中提取其指定的 HTTP 状态码；
 * 其他异常统一返回 500（内部服务器错误）。
 * 使用 LinkedHashMap 保持字段的插入顺序，确保 JSON 输出美观一致。
 */
@Component
public class GlobalErrorAttributes extends DefaultErrorAttributes {

    /**
     * 构建错误属性 Map。
     *
     * <p>返回一个有序的 LinkedHashMap，包含错误时间戳、请求路径、HTTP 状态码、
     * 异常消息以及 MDC 中的追踪上下文信息。
     *
     * @param request 当前 HTTP 请求，用于提取请求路径和异常信息
     * @param options 错误属性选项（当前使用默认值）
     * @return 包含所有错误属性的有序 Map
     */
    @Override
    public Map<String, Object> getErrorAttributes(ServerRequest request,
                                                   ErrorAttributeOptions options) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("timestamp", Instant.now().toString());
        attrs.put("path", request.path());
        attrs.put("status", resolveStatus(request));
        attrs.put("error", getError(request).getMessage());
        // 从 MDC 中提取分布式追踪信息，便于跨服务问题排查
        attrs.put("traceId", MDC.get("traceId"));
        attrs.put("spanId", MDC.get("spanId"));
        attrs.put("service", MDC.get("service"));
        return attrs;
    }

    /**
     * 根据异常类型解析对应的 HTTP 状态码。
     *
     * <p>如果异常是 LyClawException 的实例，使用其自定义的 HTTP 状态码；
     * 否则默认返回 500（内部服务器错误）。
     *
     * @param request 当前 HTTP 请求
     * @return HTTP 状态码
     */
    private int resolveStatus(ServerRequest request) {
        Throwable error = getError(request);
        // LyClawException 可以携带自定义的 HTTP 状态码
        if (error instanceof LyClawException le) {
            return le.getHttpStatus();
        }
        // 未知异常统一返回 500
        return 500;
    }
}
