package lyjew.com.lyclaw.autoconfigure.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.protocol.mcp.MCPConnector;
import lyjew.com.lyclaw.tool.ToolExecutionResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * MCP 协议的 JSON-RPC over HTTP 默认实现。
 *
 * <p>使用 JDK 内置的 HttpClient 通过 JSON-RPC 2.0 协议与 MCP Server 通信。
 * 支持 initialize / tools/list / tools/call 等标准 MCP 方法。
 *
 * <p>可替换为 SSE、stdio 等传输方式。
 */
public class McpHttpClient implements MCPConnector {

    private static final Logger log = LoggerFactory.getLogger(McpHttpClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private String serverUrl;
    private String sessionId;
    private int requestId;

    @Override
    public void connect(String serverUrl) {
        this.serverUrl = serverUrl;
        this.requestId = 0;

        try {
            Map<String, Object> initRequest = Map.of(
                    "jsonrpc", "2.0",
                    "method", "initialize",
                    "params", Map.of(
                            "protocolVersion", "2024-11-05",
                            "capabilities", Map.of(),
                            "clientInfo", Map.of("name", "LyClaw", "version", "2.0.0")
                    ),
                    "id", ++requestId
            );
            JsonNode resp = sendJsonRpc(initRequest);
            if (resp != null && resp.has("result")) {
                sessionId = resp.get("result").get("sessionId").asText();
                log.info("MCP Server {} 已连接, sessionId={}", serverUrl, sessionId);
            }
        } catch (Exception e) {
            log.error("MCP Server {} 连接失败: {}", serverUrl, e.getMessage());
        }
    }

    @Override
    public List<ToolDefinition> listTools() {
        if (serverUrl == null) return List.of();
        try {
            Map<String, Object> request = Map.of(
                    "jsonrpc", "2.0",
                    "method", "tools/list",
                    "params", Map.of(),
                    "id", ++requestId
            );
            JsonNode resp = sendJsonRpc(request);
            if (resp != null && resp.has("result") && resp.get("result").has("tools")) {
                return parseToolDefinitions(resp.get("result").get("tools"));
            }
        } catch (Exception e) {
            log.error("获取 MCP 工具列表失败: {}", e.getMessage());
        }
        return List.of();
    }

    @Override
    public ToolExecutionResult callTool(String toolName, Map<String, Object> args) {
        if (serverUrl == null) {
            return ToolExecutionResult.failure("MCP Server 未连接");
        }
        try {
            Map<String, Object> request = Map.of(
                    "jsonrpc", "2.0",
                    "method", "tools/call",
                    "params", Map.of("name", toolName, "arguments", args),
                    "id", ++requestId
            );
            JsonNode resp = sendJsonRpc(request);
            if (resp != null && resp.has("result")) {
                JsonNode content = resp.get("result").get("content");
                String text = "";
                if (content != null && content.isArray() && content.size() > 0) {
                    text = content.get(0).get("text").asText("");
                }
                return ToolExecutionResult.success(text, toolName);
            }
            return ToolExecutionResult.failure("MCP 调用返回空结果", toolName);
        } catch (Exception e) {
            log.error("MCP 工具 {} 调用失败: {}", toolName, e.getMessage());
            return ToolExecutionResult.failure(e.getMessage(), toolName);
        }
    }

    @Override
    public void disconnect() {
        serverUrl = null;
        sessionId = null;
    }

    @Override
    public boolean isConnected() {
        return serverUrl != null && sessionId != null;
    }

    private JsonNode sendJsonRpc(Map<String, Object> request) throws Exception {
        String body = mapper.writeValueAsString(request);
        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> httpResp = client.send(httpReq, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(httpResp.body());
    }

    private List<ToolDefinition> parseToolDefinitions(JsonNode toolsArray) {
        List<ToolDefinition> defs = new ArrayList<>();
        for (JsonNode node : toolsArray) {
            String name = node.get("name").asText();
            String desc = node.has("description") ? node.get("description").asText() : "";

            Map<String, Object> parameters = new LinkedHashMap<>();
            if (node.has("inputSchema")) {
                JsonNode schema = node.get("inputSchema");
                parameters.put("type", "object");
                if (schema.has("properties")) {
                    parameters.put("properties", mapper.convertValue(schema.get("properties"), Map.class));
                }
                if (schema.has("required")) {
                    List<String> required = new ArrayList<>();
                    schema.get("required").forEach(r -> required.add(r.asText()));
                    parameters.put("required", required);
                }
            }

            defs.add(ToolDefinition.builder()
                    .name(name)
                    .description(desc)
                    .parameters(parameters)
                    .source("mcp")
                    .build());
        }
        return defs;
    }
}
