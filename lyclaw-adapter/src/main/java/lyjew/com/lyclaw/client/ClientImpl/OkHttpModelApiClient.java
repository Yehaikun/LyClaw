package lyjew.com.lyclaw.client.ClientImpl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.client.ModelApiClient;
import lyjew.com.lyclaw.enums.ErrorCode;
import lyjew.com.lyclaw.exception.ModelException;
import okhttp3.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 基于 OkHttp 的模型 API 客户端实现
 *
 * 使用 OkHttp 作为 HTTP 引擎，提供同步和流式两种调用方式。
 * 流式调用通过 Reactor 的 Flux 封装，让上层可以响应式消费 SSE 事件。
 *
 * 职责边界：
 * - 负责 HTTP 通信（连接、超时、重试不需要，由上层 Adapter 处理）
 * - 负责将 HTTP 状态码映射为 ModelException
 * - 流式请求只推送原始行，SSE 解析由上层 Adapter 负责
 * - 不负责线程调度（subscribeOn 除外，那是保证阻塞 I/O 不阻塞调用线程）
 */
@Slf4j
@Component
public class OkHttpModelApiClient implements ModelApiClient {

    /** 请求超时时间（秒） */
    private static final long TIMEOUT_SECONDS = 60;

    /** OkHttp 客户端实例——线程安全，全局复用 */
    private final OkHttpClient httpClient;

    public OkHttpModelApiClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    // ==================== 同步请求 ====================

    @Override
    public String post(String url, Map<String, String> headers, String body) {
        Request request = buildRequest(url, headers, body);
        log.debug("发送同步请求: POST {}", url);

        try (Response response = httpClient.newCall(request).execute()) {
            return handleResponse(response, url);
        } catch (IOException e) {
            log.error("请求失败: url={}", url, e);
            throw ModelException.of(ErrorCode.MODEL_API_ERROR,
                    "HTTP请求失败: " + e.getMessage());
        }
    }

    // ==================== 流式请求 ====================

    @Override
    public Flux<String> postStream(String url, Map<String, String> headers, String body) {
        Request request = buildRequest(url, headers, body);
        log.debug("发送流式请求: POST {}", url);

        return Flux.<String>create(sink -> {
                    // 注意：这里用 try-with-resources 确保 Response 在任何情况下都能关闭
                    try (Response response = httpClient.newCall(request).execute()) {

                        if (!response.isSuccessful()) {
                            String errorBody = response.body() != null
                                    ? response.body().string() : "";
                            sink.error(parseHttpError(response.code(), errorBody, url));
                            return;
                        }

                        ResponseBody responseBody = response.body();
                        if (responseBody == null) {
                            sink.error(ModelException.of(ErrorCode.MODEL_RESPONSE_PARSE_ERROR,
                                    "响应体为空"));
                            return;
                        }

                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(responseBody.byteStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.isEmpty()) {
                                    continue; // SSE 事件之间的空行
                                }
                                sink.next(line);

                                if (sink.isCancelled()) {
                                    log.debug("流式请求被取消: url={}", url);
                                    break;
                                }
                            }
                        }

                        sink.complete();

                    } catch (IOException e) {
                        log.error("流式请求失败: url={}", url, e);
                        sink.error(ModelException.of(ErrorCode.MODEL_API_ERROR,
                                "流式请求失败: " + e.getMessage()));
                    }
                })
                .subscribeOn(Schedulers.boundedElastic()); // 阻塞 I/O 在弹性线程池执行
    }

    // ==================== 健康检查 ====================

    @Override
    public boolean healthCheck(String url) {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            return response.isSuccessful();
        } catch (IOException e) {
            log.debug("健康检查失败: url={}, error={}", url, e.getMessage());
            return false;
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 构建 OkHttp Request 对象
     */
    private Request buildRequest(String url, Map<String, String> headers, String body) {
        if (url == null || url.isBlank()) {
            throw ModelException.of(ErrorCode.MODEL_INVALID_REQUEST, "URL 不能为空");
        }

        Request.Builder builder = new Request.Builder().url(url);

        // 设置请求头
        if (headers != null) {
            headers.forEach(builder::addHeader);
        }

        // 设置请求体
        RequestBody requestBody = body != null
                ? RequestBody.create(body, MediaType.parse("application/json"))
                : RequestBody.create("", MediaType.parse("application/json"));

        return builder.post(requestBody).build();
    }

    /**
     * 处理 HTTP 响应，成功时返回响应体，失败时抛异常
     */
    private String handleResponse(Response response, String url) throws IOException {
        int httpStatus = response.code();
        ResponseBody body = response.body();
        String bodyString = body != null ? body.string() : "";

        if (response.isSuccessful()) {
            return bodyString;
        }

        // 失败——根据状态码抛不同的异常
        throw parseHttpError(httpStatus, bodyString, url);
    }

    /**
     * 根据 HTTP 状态码构造对应的 ModelException
     */
    private ModelException parseHttpError(int httpStatus, String bodyString, String url) {
        log.error("API调用失败: url={}, status={}, body={}",
                url, httpStatus, bodyString);

        switch (httpStatus) {
            case 401:
                return ModelException.of(ErrorCode.MODEL_API_INVALID_KEY,
                        "状态码=" + httpStatus);
            case 403:
                return ModelException.of(ErrorCode.MODEL_API_FORBIDDEN,
                        "状态码=" + httpStatus);
            case 429:
                return ModelException.of(ErrorCode.MODEL_API_RATE_LIMITED,
                        "状态码=" + httpStatus);
            case 500:
            case 502:
            case 503:
                return ModelException.of(ErrorCode.MODEL_API_ERROR,
                        "服务器错误, 状态码=" + httpStatus);
            default:
                return ModelException.withRawResponse(httpStatus,
                        "模型API返回错误, 状态码=" + httpStatus, bodyString);
        }
    }
}