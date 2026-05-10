package lyjew.com.lyclaw.client;

import lyjew.com.lyclaw.exception.ModelException;
import reactor.core.publisher.Flux;

import java.util.Map;

public interface ModelApiClient {

    /**
     * @param url     完整请求 URL（含端点路径）
     * @param headers 请求头（Authorization, Content-Type）
     * @param body    请求体 JSON 字符串
     * @return 响应体字符串
     * @throws ModelException 网络错误或非 2xx 响应时抛出
     */
    String post(String url, Map<String, String> headers, String body);

    /**
     * @param url     完整请求 URL
     * @param headers 请求头
     * @param body    请求体 JSON 字符串
     * @return SSE 事件文本流，每个元素一行 "data: {...}"
     * @throws ModelException 网络错误时抛出
     */
    Flux<String> postStream(String url, Map<String, String> headers, String body);

    /**
     * @param url 健康检查 URL
     * @return true 表示服务可达且返回 2xx
     */
    boolean healthCheck(String url);
}
