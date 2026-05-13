package lyjew.com.lyclaw.action.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lyjew.com.lyclaw.annotation.tool.Tool;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.ToolExecutionResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tavily 搜索引擎工具，同时使用 {@code @Tool} 注解和实现 {@link lyjew.com.lyclaw.tool.Tool} 接口。
 *
 * <p>注解提供声明式元数据（名称、描述、分组），接口提供完整执行契约。
 * {@code ToolAnnotationProcessor} 检测到 bean 同时满足两者时，直接注册而不走 adapter 包装。</p>
 */
@Tool(name = "web_search",
      description = "搜索互联网获取最新信息，基于Tavily搜索引擎",
      readonly = true,
      group = "builtin")
public class TavilyWebSearchTool implements lyjew.com.lyclaw.tool.Tool {

    private static final String TAVILY_API_URL = "https://api.tavily.com/search";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public ToolExecutionResult execute(ToolCall toolCall, ChatContext context) {
        long start = System.currentTimeMillis();
        try {
            String query = extractQuery(toolCall.getArguments());
            if (query == null || query.isBlank()) {
                return ToolExecutionResult.failure("搜索关键词为空", "web_search");
            }

            String apiKey = System.getenv("TAVILY_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                return ToolExecutionResult.failure(
                        "Tavily API Key 未配置，请设置环境变量 TAVILY_API_KEY", "web_search");
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("api_key", apiKey);
            body.put("query", query);
            body.put("search_depth", "basic");
            body.put("include_answer", true);
            body.put("max_results", 5);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TAVILY_API_URL))
                    .header("Content-Type", "application/json")
                    .timeout(HTTP_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String formatted = formatResponse(response.body());
                long elapsed = System.currentTimeMillis() - start;
                return ToolExecutionResult.builder()
                        .success(true)
                        .result(formatted)
                        .toolName("web_search")
                        .elapsedMs(elapsed)
                        .build();
            } else {
                return ToolExecutionResult.failure(
                        "Tavily API 返回错误状态码: " + response.statusCode(), "web_search");
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return ToolExecutionResult.builder()
                    .success(false)
                    .error("搜索失败: " + e.getMessage())
                    .toolName("web_search")
                    .elapsedMs(elapsed)
                    .build();
        }
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", Map.of(
                "query", Map.of("type", "string", "description", "搜索关键词")
        ));
        params.put("required", List.of("query"));

        return ToolDefinition.builder()
                .name("web_search")
                .description("搜索互联网获取最新信息，基于Tavily搜索引擎")
                .parameters(params)
                .source("builtin")
                .readOnly(true)
                .build();
    }

    private String extractQuery(String arguments) {
        try {
            JsonNode node = MAPPER.readTree(arguments);
            if (node.has("query")) {
                return node.get("query").asText();
            }
        } catch (Exception e) {
            // 非 JSON 格式时当作纯文本查询
        }
        return arguments;
    }

    private String formatResponse(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            StringBuilder sb = new StringBuilder();

            if (root.has("answer") && !root.get("answer").isNull()) {
                sb.append("答案摘要:\n");
                sb.append(root.get("answer").asText()).append("\n\n");
            }

            if (root.has("results") && root.get("results").isArray()) {
                sb.append("搜索结果:\n\n");
                int i = 1;
                for (JsonNode r : root.get("results")) {
                    sb.append(i++).append(". ");
                    sb.append(r.get("title").asText()).append("\n");
                    sb.append("   URL: ").append(r.get("url").asText()).append("\n");
                    if (r.has("content")) {
                        String content = r.get("content").asText();
                        if (content.length() > 300) {
                            content = content.substring(0, 300) + "...";
                        }
                        sb.append("   ").append(content).append("\n");
                    }
                    sb.append("\n");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            return responseBody;
        }
    }
}
