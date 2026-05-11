package lyjew.com.lyclaw.action.tool;

import lyjew.com.lyclaw.framework.annotation.Tool;
import lyjew.com.lyclaw.framework.annotation.Param;
import lyjew.com.lyclaw.framework.annotation.ToolCondition;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Tool(name = "web_search",
      description = "搜索互联网获取最新信息",
      readonly = true,
      group = "builtin")
@ToolCondition(requiresConfig = "lyclaw.tools.brave.api-key")
public class AnnotatedWebSearchTool {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

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

    private String performSearch(String query) {
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
                // Fall back to mock result
            }
        }

        return "搜索结果: '" + query + "'\n"
                + "======================================\n"
                + "提示：搜索 API Key 尚未配置或 API 调用失败，当前为模拟结果。\n"
                + "======================================\n";
    }
}
