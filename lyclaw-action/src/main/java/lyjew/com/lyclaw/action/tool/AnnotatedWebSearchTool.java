package lyjew.com.lyclaw.action.tool;

import lyjew.com.lyclaw.annotation.tool.Tool;
import lyjew.com.lyclaw.annotation.tool.Param;
import lyjew.com.lyclaw.annotation.tool.ToolCondition;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 互联网搜索工具，通过 Brave Search API 执行网络搜索。
 *
 * <p>该工具为只读操作，group 为 "builtin"。需要配置
 * {@code SEARCH_API_KEY} 环境变量（Brave API Key）才能获取真实搜索结果。
 * 通过 {@code @ToolCondition} 注解条件化激活。</p>
 *
 * <p>如果没有配置 API Key 或 API 调用失败，则返回模拟的占位搜索结果。</p>
 */
@Tool(name = "web_search",
      description = "搜索互联网获取最新信息",
      readonly = true,
      group = "builtin")
@ToolCondition(requiresConfig = "lyclaw.tools.brave.api-key")
public class AnnotatedWebSearchTool {

    /** HTTP 请求超时时间（15 秒） */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    /** 共享 HTTP 客户端，启用重定向跟随 */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 执行网络搜索。
     *
     * @param query 搜索关键词
     * @return 搜索结果 JSON 或模拟结果
     */
    public String search(
        @Param(name = "query", description = "搜索关键词")
        String query
    ) {
        if (query == null || query.isBlank()) {
            return "搜索失败：搜索关键词为空";
        }
        try {
            return performSearch(query);
        } catch (Exception e) {
            return "搜索失败: " + e.getMessage();
        }
    }

    /**
     * 通过 Brave Search API 执行实际的搜索请求。
     *
     * <p>从环境变量 SEARCH_API_KEY 获取 API Key。如果 Key 不存在
     * 或 API 返回非 200，则返回模拟结果。</p>
     *
     * @param query 搜索关键词
     * @return 搜索结果 JSON 字符串
     */
    private String performSearch(String query) {
        // 从环境变量读取 Brave Search API Key
        String apiKey = System.getenv("SEARCH_API_KEY");
        if (apiKey != null && !apiKey.isEmpty()) {
            try {
                String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(java.net.URI.create(
                                "https://api.search.brave.com/res/v1/web/search?q=" + encodedQuery))
                        .header("Accept", "application/json")
                        .header("X-Subscription-Token", apiKey)
                        .timeout(HTTP_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<String> response = HTTP_CLIENT.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return response.body();
                }
            } catch (Exception e) {
                // API 调用失败，降级到模拟结果
            }
        }

        // 返回模拟搜索结果
        return "搜索结果: '" + query + "'\n"
                + "======================================\n"
                + "提示：搜索 API Key 尚未配置或 API 调用失败，当前为模拟结果。\n"
                + "======================================\n";
    }
}
