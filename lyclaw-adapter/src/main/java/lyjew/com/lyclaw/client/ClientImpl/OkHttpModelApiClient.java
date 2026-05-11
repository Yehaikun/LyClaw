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
 * 基于 OkHttp 的模型 API 客户端实现。
 *
 * <p>负责向大模型服务端发送 HTTP 请求，支持：</p>
 * <ul>
 *   <li>同步 POST 请求 -- 发送请求并等待完整响应</li>
 *   <li>流式 POST 请求 -- 返回 SSE (Server-Sent Events) 格式的 Flux 流，
 *       适用于 ChatGPT/DeepSeek 等模型的流式补全</li>
 *   <li>健康检查 -- 对指定 URL 发送 GET 请求检测连通性</li>
 * </ul>
 *
 * <p>连接超时、读超时、写超时均设置为 300 秒，以适应大模型长时间推理场景。</p>
 */
@Slf4j
@Component
public class OkHttpModelApiClient implements ModelApiClient {

    /** HTTP 请求超时时间（秒），适应大模型推理耗时较长的特点 */
    private static final long TIMEOUT_SECONDS = 300;

    private final OkHttpClient httpClient;

    public OkHttpModelApiClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 发送同步 POST 请求并返回响应体字符串。
     *
     * <p>适用于非流式对话补全场景，会阻塞直到收到完整响应。</p>
     *
     * @param url     请求 URL
     * @param headers 请求头键值对
     * @param body    请求体 JSON 字符串
     * @return 响应体字符串
     * @throws ModelException HTTP 请求失败或返回错误状态码时抛出
     */
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

    /**
     * 发送流式 POST 请求，返回 SSE 事件行的 Flux 流。
     *
     * <p>使用 Project Reactor 的 {@link Flux#generate} 模式逐行读取响应。
     * 连接在订阅时建立，在订阅者取消或读取完成时自动关闭。</p>
     *
     * @param url     请求 URL
     * @param headers 请求头键值对
     * @param body    请求体 JSON 字符串
     * @return 每行为一个 SSE 数据行的 Flux 流
     */
    @Override
    public Flux<String> postStream(String url, Map<String, String> headers, String body) {
        Request request = buildRequest(url, headers, body);
        log.debug("发送流式请求: POST {}", url);

        return Flux.<String, StreamContext>generate(
                // 初始化：建立连接并检查 HTTP 状态码
                () -> {
                    try {
                        Response response = httpClient.newCall(request).execute();

                        if (!response.isSuccessful()) {
                            String errorBody = response.body() != null
                                    ? response.body().string() : "";
                            response.close();
                            throw parseHttpError(response.code(), errorBody, url);
                        }

                        ResponseBody responseBody = response.body();
                        if (responseBody == null) {
                            response.close();
                            throw ModelException.of(ErrorCode.MODEL_RESPONSE_PARSE_ERROR,
                                    "响应体为空");
                        }

                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(responseBody.byteStream()));
                        return new StreamContext(response, responseBody, reader);
                    } catch (IOException e) {
                        throw ModelException.of(ErrorCode.MODEL_API_ERROR,
                                "流式请求失败: " + e.getMessage());
                    }
                },
                // 逐行读取：每次从 BufferedReader 中取一行并发射
                (ctx, sink) -> {
                    try {
                        String line;
                        while ((line = ctx.reader.readLine()) != null) {
                            if (!line.isEmpty()) {
                                sink.next(line);
                                return ctx;
                            }
                        }
                        ctx.close();
                        sink.complete();
                    } catch (IOException e) {
                        ctx.close();
                        sink.error(ModelException.of(ErrorCode.MODEL_API_ERROR,
                                "流式读取失败: " + e.getMessage()));
                    }
                    return ctx;
                },
                // 清理回调：在流终止或取消时关闭所有资源
                StreamContext::close
        ).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 流式请求的上下文对象，持有响应、响应体和读取器，
     * 在流结束时需要统一关闭以释放 HTTP 连接。
     */
    private static class StreamContext {
        final Response response;
        final ResponseBody body;
        final BufferedReader reader;

        StreamContext(Response response, ResponseBody body, BufferedReader reader) {
            this.response = response;
            this.body = body;
            this.reader = reader;
        }

        /** 依次关闭 reader、body、response，忽略各步骤的异常。 */
        void close() {
            try { reader.close(); } catch (IOException ignored) {}
            try { body.close(); } catch (Exception ignored) {}
            try { response.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 对指定 URL 发送 GET 请求进行连通性检查。
     *
     * @param url 待检查的 URL
     * @return true 表示 HTTP 状态码为 2xx
     */
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

    /**
     * 构建 OkHttp POST 请求对象。
     *
     * @param url     请求 URL，不能为空
     * @param headers 请求头键值对，可为 null
     * @param body    请求体 JSON 字符串，可为 null（发送空体）
     * @return OkHttp Request 对象
     * @throws ModelException URL 为空时抛出
     */
    private Request buildRequest(String url, Map<String, String> headers, String body) {
        if (url == null || url.isBlank()) {
            throw ModelException.of(ErrorCode.MODEL_INVALID_REQUEST, "URL 不能为空");
        }

        Request.Builder builder = new Request.Builder().url(url);

        if (headers != null) {
            headers.forEach(builder::addHeader);
        }

        RequestBody requestBody = body != null
                ? RequestBody.create(body, MediaType.parse("application/json"))
                : RequestBody.create("", MediaType.parse("application/json"));

        return builder.post(requestBody).build();
    }

    /**
     * 处理 HTTP 响应，成功时返回响应体字符串，失败时抛出对应异常。
     *
     * @param response OkHttp Response 对象
     * @param url      请求 URL，用于错误日志
     * @return 响应体字符串
     * @throws IOException    读取响应体失败时抛出
     * @throws ModelException HTTP 状态码非 2xx 时抛出
     */
    private String handleResponse(Response response, String url) throws IOException {
        int httpStatus = response.code();
        ResponseBody body = response.body();
        String bodyString = body != null ? body.string() : "";

        if (response.isSuccessful()) {
            return bodyString;
        }

        throw parseHttpError(httpStatus, bodyString, url);
    }

    /**
     * 根据 HTTP 状态码解析为对应的业务异常。
     *
     * <ul>
     *   <li>401 -- API Key 无效</li>
     *   <li>403 -- 权限不足</li>
     *   <li>429 -- 请求频率过高</li>
     *   <li>5xx -- 服务端错误</li>
     * </ul>
     *
     * @param httpStatus HTTP 状态码
     * @param bodyString 响应体内容，用于错误详情
     * @param url        请求 URL
     * @return 对应的 ModelException
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
                // 其余状态码携带原始响应体信息
                return ModelException.withRawResponse(httpStatus,
                        "模型API返回错误, 状态码=" + httpStatus, bodyString);
        }
    }
}
