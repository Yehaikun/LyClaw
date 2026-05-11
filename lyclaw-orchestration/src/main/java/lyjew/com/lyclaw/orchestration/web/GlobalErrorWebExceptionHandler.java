package lyjew.com.lyclaw.orchestration.web;

import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.autoconfigure.web.reactive.error.AbstractErrorWebExceptionHandler;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.reactive.error.ErrorAttributes;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 全局错误 Web 异常处理器，基于 Spring WebFlux 的响应式错误处理。
 *
 * <p>职责：拦截 WebFlux 应用中的所有未捕获异常，将错误信息以 JSON 格式统一返回。
 * 使用自定义的 GlobalErrorAttributes 来构建错误响应体，包含以下字段：
 * <ul>
 *   <li>timestamp：错误发生时间</li>
 *   <li>path：请求路径</li>
 *   <li>status：HTTP 状态码</li>
 *   <li>error：错误消息</li>
 *   <li>traceId / spanId / service：从 MDC 中获取的分布式追踪信息</li>
 * </ul>
 *
 * <p>通过 @Order(-2) 确保该处理器优先于 Spring Boot 默认的 ErrorWebExceptionHandler，
 * 从而接管所有错误响应的渲染。匹配所有请求路径（RequestPredicates.all()），
 * 以 JSON 格式（APPLICATION_JSON）返回错误属性。
 */
@Component
@Order(-2)
public class GlobalErrorWebExceptionHandler extends AbstractErrorWebExceptionHandler {

    /**
     * 构造全局错误处理器。
     *
     * @param errorAttributes       自定义错误属性提供者，负责构建错误响应体中的字段
     * @param webProperties         Web 配置属性，用于获取静态资源配置
     * @param applicationContext    Spring 应用上下文
     * @param serverCodecConfigurer 编解码器配置，用于设置消息的读写器
     */
    public GlobalErrorWebExceptionHandler(GlobalErrorAttributes errorAttributes,
                                          WebProperties webProperties,
                                          ApplicationContext applicationContext,
                                          ServerCodecConfigurer serverCodecConfigurer) {
        super(errorAttributes, webProperties.getResources(), applicationContext);
        // 配置 JSON 序列化/反序列化所需的读写器
        this.setMessageReaders(serverCodecConfigurer.getReaders());
        this.setMessageWriters(serverCodecConfigurer.getWriters());
    }

    /**
     * 定义路由规则：匹配所有请求，使用 renderErrorResponse 方法处理错误响应。
     *
     * @param errorAttributes 错误属性提供者（由构造器传入的 GlobalErrorAttributes）
     * @return 匹配所有请求的路由函数
     */
    @Override
    protected RouterFunction<ServerResponse> getRoutingFunction(ErrorAttributes errorAttributes) {
        return RouterFunctions.route(RequestPredicates.all(), this::renderErrorResponse);
    }

    /**
     * 渲染错误响应为 JSON 格式。
     *
     * <p>从 errorAttributes 中获取 HTTP 状态码（默认为 500），
     * 构建对应状态码的响应，并以 JSON 格式返回完整的错误属性 Map。
     *
     * @param request 当前 HTTP 请求
     * @return 包含 JSON 错误体的 ServerResponse Mono
     */
    private Mono<ServerResponse> renderErrorResponse(ServerRequest request) {
        Map<String, Object> errorAttributes = getErrorAttributes(request,
                ErrorAttributeOptions.defaults());
        // 从错误属性中提取 HTTP 状态码，未指定时默认 500
        int httpStatus = (int) errorAttributes.getOrDefault("status", 500);
        return ServerResponse.status(HttpStatus.valueOf(httpStatus))
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(errorAttributes));
    }
}
