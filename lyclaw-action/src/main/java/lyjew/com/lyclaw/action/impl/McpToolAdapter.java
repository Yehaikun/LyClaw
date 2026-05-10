package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Slf4j
public class McpToolAdapter implements Tool {

    private final String name;
    private final String description;
    private final Map<String, Object> parameters;
    private final String category;
    private final String endpointUrl;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    public McpToolAdapter(String name, String description,
                          Map<String, Object> parameters, String category,
                          String endpointUrl) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.category = category;
        this.endpointUrl = endpointUrl;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ToolResult execute(ToolCall toolCall, ChatContext context) {
        long startTime = System.currentTimeMillis();
        try {
            String requestBody = buildMcpRequest(toolCall);
            log.debug("发送 MCP 请求: tool={}, endpoint={}", name, endpointUrl);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpointUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(REQUEST_TIMEOUT)
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - startTime;

            if (response.statusCode() == 200) {
                String extracted = extractResult(response.body());
                return new ToolResult(true, extracted, null, elapsed, 0);
            } else {
                log.warn("MCP 服务返回非 200: status={}", response.statusCode());
                return new ToolResult(false, null,
                        "MCP 服务返回错误: HTTP " + response.statusCode(), elapsed, 0);
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("MCP 工具执行失败: tool={}", name, e);
            return new ToolResult(false, null,
                    "MCP 工具执行失败: " + e.getMessage(), elapsed, 0);
        }
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(name)
                .description(description)
                .parameters(parameters)
                .source("mcp")
                .build();
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public String getCategory() {
        return category;
    }

    private String buildMcpRequest(ToolCall toolCall) {
        String argsJson = toolCall.getArguments() != null
                ? toolCall.getArguments() : "{}";
        return "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + escapeJson(name)
                + "\",\"arguments\":" + argsJson + "}}";
    }

    private String extractResult(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return "";
        }
        int contentIdx = responseBody.indexOf("\"content\"");
        if (contentIdx >= 0) {
            int colonIdx = responseBody.indexOf(":", contentIdx);
            if (colonIdx >= 0) {
                int start = colonIdx + 1;
                while (start < responseBody.length()
                        && (responseBody.charAt(start) == ' '
                        || responseBody.charAt(start) == '\"')) {
                    start++;
                }
                if (start < responseBody.length()) {
                    int end = responseBody.indexOf("\"", start);
                    if (end >= 0 && end > start) {
                        return responseBody.substring(start, end);
                    }
                }
            }
        }
        return responseBody;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
