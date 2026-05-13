package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * MCP（Model Context Protocol）工具适配器，将远程 MCP 服务暴露为本地 Tool 接口。
 *
 * <p>MCP 是一种标准化协议，允许 LLM 通过 HTTP 调用外部工具服务。
 * 该类封装了 MCP 协议的 JSON-RPC 2.0 请求格式，将本地工具调用请求
 * 转发到指定的远程 MCP 服务端点，并解析返回结果。</p>
 *
 * <p>工作流程：
 * <ol>
 *   <li>接收 ToolCall 请求</li>
 *   <li>构建 JSON-RPC 2.0 请求体：{@code {"jsonrpc":"2.0","method":"tools/call","params":{...}}}</li>
 *   <li>通过 HTTP POST 发送到 MCP 端点</li>
 *   <li>从响应中提取 content 字段作为工具执行结果</li>
 * </ol>
 * </p>
 *
 * <p>注意：该类不由 Spring 容器直接管理，而是通过
 * {@link DefaultToolRegistry#registerMcpTool} 动态创建和注册。</p>
 */
@Slf4j
public class McpToolAdapter implements Tool {

    /** 工具名称 */
    private final String name;
    /** 工具描述 */
    private final String description;
    /** 工具参数定义（JSON Schema 格式） */
    private final Map<String, Object> parameters;
    /** 工具类别 */
    private final String category;
    /** MCP 服务的 HTTP 端点 URL */
    private final String endpointUrl;

    /** 共享的 HTTP 客户端，连接超时 10 秒 */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 单次 MCP 请求的超时时间（30 秒） */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 构造 MCP 工具适配器。
     *
     * @param name        工具名称
     * @param description 工具描述
     * @param parameters  参数 JSON Schema
     * @param category    工具类别
     * @param endpointUrl MCP 服务端点 URL
     */
    public McpToolAdapter(String name, String description,
                          Map<String, Object> parameters, String category,
                          String endpointUrl) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.category = category;
        this.endpointUrl = endpointUrl;
    }

    /**
     * 返回工具名称。
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * 执行 MCP 工具调用。
     *
     * <p>构建 JSON-RPC 2.0 请求体，通过 HTTP POST 发送到 MCP 端点。
     * 响应状态码 200 时提取 content 字段作为结果；非 200 时返回错误。</p>
     *
     * @param toolCall 工具调用请求（含参数）
     * @param context  对话上下文（当前未使用）
     * @return 执行结果
     */
    @Override
    public ToolExecutionResult execute(ToolCall toolCall, ChatContext context) {
        long startTime = System.currentTimeMillis();
        try {
            // 构建 JSON-RPC 2.0 请求体
            String requestBody = buildMcpRequest(toolCall);
            log.debug("发送 MCP 请求: tool={}, endpoint={}", name, endpointUrl);

            // 发送 HTTP POST 请求
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
                // 从 JSON-RPC 响应中提取 content 字段
                String extracted = extractResult(response.body());
                return ToolExecutionResult.builder()
                        .success(true)
                        .result(extracted)
                        .elapsedMs(elapsed)
                        .build();
            } else {
                log.warn("MCP 服务返回非 200: status={}", response.statusCode());
                return ToolExecutionResult.builder()
                        .success(false)
                        .error("MCP 服务返回错误: HTTP " + response.statusCode())
                        .elapsedMs(elapsed)
                        .build();
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("MCP 工具执行失败: tool={}", name, e);
            return ToolExecutionResult.builder()
                    .success(false)
                    .error("MCP 工具执行失败: " + e.getMessage())
                    .elapsedMs(elapsed)
                    .build();
        }
    }

    /**
     * 获取工具的静态定义（名称、描述、参数 Schema、来源标记为 "mcp"）。
     *
     * @return ToolDefinition 对象
     */
    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(name)
                .description(description)
                .parameters(parameters)
                .source("mcp")
                .build();
    }

    /**
     * 返回 MCP 端点 URL。
     */
    public String getEndpointUrl() {
        return endpointUrl;
    }

    /**
     * 返回工具类别。
     */
    public String getCategory() {
        return category;
    }

    /**
     * 构建 JSON-RPC 2.0 请求体。
     *
     * <p>格式：{@code {"jsonrpc":"2.0","method":"tools/call","params":{"name":"<工具名>","arguments":<参数JSON>}}}</p>
     *
     * @param toolCall 工具调用请求
     * @return JSON-RPC 请求体字符串
     */
    private String buildMcpRequest(ToolCall toolCall) {
        String argsJson = toolCall.getArguments() != null
                ? toolCall.getArguments() : "{}";
        return "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + escapeJson(name)
                + "\",\"arguments\":" + argsJson + "}}";
    }

    /**
     * 从 MCP JSON-RPC 响应中提取 content 字段的值。
     *
     * <p>使用简单的字符串索引方式查找 "{@code "content"}" 键，
     * 然后提取其后的字符串值。如果找不到 content 字段，则返回整个响应体。</p>
     *
     * @param responseBody MCP 服务返回的 JSON 响应体
     * @return 提取的 content 值
     */
    private String extractResult(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return "";
        }
        // 简单字符串解析：查找 "content" 键并提取其值
        int contentIdx = responseBody.indexOf("\"content\"");
        if (contentIdx >= 0) {
            int colonIdx = responseBody.indexOf(":", contentIdx);
            if (colonIdx >= 0) {
                int start = colonIdx + 1;
                // 跳过冒号后的空格和引号
                while (start < responseBody.length()
                        && (responseBody.charAt(start) == ' '
                        || responseBody.charAt(start) == '\"')) {
                    start++;
                }
                if (start < responseBody.length()) {
                    // 查找字符串值的结束引号
                    int end = responseBody.indexOf("\"", start);
                    if (end >= 0 && end > start) {
                        return responseBody.substring(start, end);
                    }
                }
            }
        }
        // 降级：返回原始响应体
        return responseBody;
    }

    /**
     * 对字符串中的特殊 JSON 字符进行转义，使其可安全嵌入 JSON 字符串值中。
     *
     * <p>转义字符：反斜杠、双引号、换行、回车、制表符。</p>
     *
     * @param s 原始字符串
     * @return 转义后的字符串
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
