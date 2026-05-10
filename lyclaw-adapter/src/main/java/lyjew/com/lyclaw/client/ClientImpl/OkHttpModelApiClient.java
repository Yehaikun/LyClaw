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

@Slf4j
@Component
public class OkHttpModelApiClient implements ModelApiClient {

    private static final long TIMEOUT_SECONDS = 300;

    private final OkHttpClient httpClient;

    public OkHttpModelApiClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

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

    @Override
    public Flux<String> postStream(String url, Map<String, String> headers, String body) {
        Request request = buildRequest(url, headers, body);
        log.debug("发送流式请求: POST {}", url);

        return Flux.<String>create(sink -> {
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
                                    continue;
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
                .subscribeOn(Schedulers.boundedElastic());
    }

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

    private String handleResponse(Response response, String url) throws IOException {
        int httpStatus = response.code();
        ResponseBody body = response.body();
        String bodyString = body != null ? body.string() : "";

        if (response.isSuccessful()) {
            return bodyString;
        }

        throw parseHttpError(httpStatus, bodyString, url);
    }

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
