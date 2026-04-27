package lyjew.com.lyclaw.client;

import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 模型 API HTTP 客户端接口
 *
 * 封装 HTTP 通信细节，让上层的适配器只关心业务逻辑。
 * 当前使用 OkHttp 实现，未来可切换为 WebClient、RestTemplate 等。
 *
 * 设计模式：适配器模式（把 OkHttp 的 API 适配成我们需要的简洁接口）
 */
public interface ModelApiClient {

    /**
     * 发送同步 POST 请求
     *
     * @param url     完整的请求 URL（含端点路径）
     * @param headers 请求头（如 Authorization、Content-Type）
     * @param body    请求体 JSON 字符串
     * @return 响应体字符串
     * @throws lyjew.com.lyclaw.exception.ModelException 网络错误或非 2xx 响应时抛出
     */
    String post(String url, Map<String, String> headers, String body);

    /**
     * 发送流式 POST 请求（SSE 格式）
     *
     * @param url     完整的请求 URL
     * @param headers 请求头
     * @param body    请求体 JSON 字符串
     * @return SSE 事件的文本流，每个元素是一行 "data: {...}"
     * @throws lyjew.com.lyclaw.exception.ModelException 网络错误时抛出
     */
    Flux<String> postStream(String url, Map<String, String> headers, String body);

    /**
     * 健康检查——向指定 URL 发送 GET 请求
     *
     * @param url 健康检查 URL
     * @return true 表示服务可达且返回 2xx
     */
    boolean healthCheck(String url);
}