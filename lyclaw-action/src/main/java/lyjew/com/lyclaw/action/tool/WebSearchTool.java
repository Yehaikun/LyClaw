package lyjew.com.lyclaw.action.tool;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Deprecated
public class WebSearchTool implements Tool {

    private static final String TOOL_NAME = "web_search";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public ToolResult execute(ToolCall toolCall, ChatContext context) {
        long startTime = System.currentTimeMillis();
        try {
            String query = extractQuery(toolCall);
            if (query == null || query.isBlank()) {
                return ToolResult.failure("搜索失败：搜索关键词为空");
            }
            log.info("搜索关键词: {}", query);
            String result = performSearch(query);
            long elapsed = System.currentTimeMillis() - startTime;
            return new ToolResult(true, result, null, elapsed, 0);
        } catch (Exception e) {
            log.error("搜索异常", e);
            return ToolResult.failure("搜索失败: " + e.getMessage());
        }
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .source("builtin")
                .description("搜索互联网获取实时信息。当需要了解新闻、天气、最新数据或其他实时信息时使用。")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "searchQuery", Map.of(
                                        "type", "string",
                                        "description", "要搜索的关键词"
                                )
                        ),
                        "required", List.of("searchQuery")
                ))
                .build();
    }

    private String extractQuery(ToolCall toolCall) {
        String args = toolCall.getArguments();
        if (args == null || args.isEmpty()) return "";
        int queryIdx = args.indexOf("\"searchQuery\"");
        if (queryIdx < 0) return args.replaceAll("\"", "").trim();
        int start = args.indexOf("\"", args.indexOf(":", queryIdx) + 1) + 1;
        int end = args.indexOf("\"", start);
        if (start > 0 && end > start) return args.substring(start, end);
        return "";
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
                log.warn("搜索 API 返回非 200: {}", response.statusCode());
            } catch (Exception e) {
                log.warn("搜索 API 调用失败，回退到模拟结果", e);
            }
        }

        log.debug("使用模拟搜索结果（未配置搜索 API Key 或 API 调用失败）");
        return "搜索结果: '" + query + "'\n"
                + "======================================\n"
                + "提示：搜索 API Key 尚未配置或 API 调用失败，当前为模拟结果。\n"
                + "======================================\n";
    }
}
