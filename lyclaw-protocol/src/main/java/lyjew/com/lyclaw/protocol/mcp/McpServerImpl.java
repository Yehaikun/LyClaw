package lyjew.com.lyclaw.protocol.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.action.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP (Model Context Protocol) 服务器实现，支持STDIO和SSE两种传输方式。
 * <p>
 * 实现了JSON-RPC 2.0协议，提供工具(tools)、资源(resources)、提示词(prompts)的注册、查询和执行功能。
 * 使用虚拟线程处理并发请求，所有状态通过ConcurrentHashMap保证线程安全。
 * </p>
 */
@Slf4j
@Component
public class McpServerImpl implements McpServer {

    @Getter @Setter private String serverName = "lyclaw-mcp-server";
    @Getter @Setter private String serverVersion = "0.0.1";
    @Getter private McpTransportType transportType = McpTransportType.STDIO;

    private final Map<String, McpToolDescriptor> tools = new ConcurrentHashMap<>();
    private final Map<String, McpResourceDescriptor> resources = new ConcurrentHashMap<>();
    private final Map<String, McpPromptDescriptor> prompts = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong requestIdCounter = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private Process subprocess;
    private Thread stdinReaderThread;

    public void setTransportType(McpTransportType type) { this.transportType = type; }

    /**
     * 启动MCP服务器，根据传输类型选择对应的启动策略。
     * 通过CAS保证只启动一次，避免重复启动。
     */
    @Override
    public void start() {
        // CAS保证只启动一次
        if (!running.compareAndSet(false, true)) {
            log.warn("Already running, ignoring duplicate start()");
            return;
        }
        log.info("Starting with transport={}, name={}, version={}", transportType, serverName, serverVersion);
        switch (transportType) {
            case STDIO -> startStdioTransport();
            case SSE -> startSseTransport();
            case WEBSOCKET -> log.info("WebSocket transport reserved for future use");
        }
    }

    /**
     * 停止MCP服务器，销毁子进程，清空所有注册的工具/资源/提示词。
     */
    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        log.info("Stopping server...");
        // 销毁子进程，最多等待5秒
        if (subprocess != null && subprocess.isAlive()) {
            subprocess.destroy();
            try { subprocess.waitFor(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); subprocess.destroyForcibly(); }
        }
        // 中断stdin读取线程
        if (stdinReaderThread != null && stdinReaderThread.isAlive()) stdinReaderThread.interrupt();
        tools.clear(); resources.clear(); prompts.clear();
        log.info("Stopped");
    }

    /** @return 服务器是否正在运行 */
    public boolean isRunning() { return running.get(); }

    /**
     * 注册一个MCP工具。
     *
     * @param tool 工具描述符
     */
    @Override
    public void registerTool(McpToolDescriptor tool) {
        tools.put(tool.getName(), tool);
        log.info("Registered tool: {} (server: {})", tool.getName(), tool.getServerName());
    }

    /**
     * 注册一个MCP资源。
     *
     * @param resource 资源描述符
     */
    @Override
    public void registerResource(McpResourceDescriptor resource) {
        resources.put(resource.getUri(), resource);
        log.info("Registered resource: {} ({})", resource.getUri(), resource.getName());
    }

    /**
     * 注册一个MCP提示词。
     *
     * @param prompt 提示词描述符
     */
    @Override
    public void registerPrompt(McpPromptDescriptor prompt) {
        prompts.put(prompt.getName(), prompt);
        log.info("Registered prompt: {} ({} args)", prompt.getName(),
                prompt.getArguments() != null ? prompt.getArguments().size() : 0);
    }

    @Override
    public Set<McpToolDescriptor> listTools() { return Set.copyOf(tools.values()); }

    public Set<McpResourceDescriptor> listResources() { return Set.copyOf(resources.values()); }
    public Set<McpPromptDescriptor> listPrompts() { return Set.copyOf(prompts.values()); }

    /**
     * 执行指定工具。
     *
     * @param toolName 工具名称
     * @param args     工具参数
     * @return 工具执行结果的CompletableFuture
     */
    @Override
    public CompletableFuture<ToolResult> executeTool(String toolName, Map<String, Object> args) {
        McpToolDescriptor descriptor = tools.get(toolName);
        if (descriptor == null) {
            log.warn("Tool not found: {}", toolName);
            return CompletableFuture.completedFuture(
                    ToolResult.builder().toolName(toolName).success(false)
                            .errorMessage("Tool not found: " + toolName).output(null).durationMs(0).build());
        }
        long start = System.currentTimeMillis();
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Executing tool: {} with args: {}", toolName, args);
                String output = buildToolExecutionOutput(descriptor, args);
                long duration = System.currentTimeMillis() - start;
                return ToolResult.builder().toolName(toolName).success(true)
                        .output(output).durationMs(duration)
                        .metadata(Map.of("serverName", descriptor.getServerName(), "args", args)).build();
            } catch (Exception e) {
                log.error("Tool execution failed: {}", toolName, e);
                return ToolResult.builder().toolName(toolName).success(false)
                        .errorMessage("Execution failed: " + e.getMessage()).output(null)
                        .durationMs(System.currentTimeMillis() - start).build();
            }
        }, executor);
    }

    /**
     * 处理JSON-RPC请求，根据method字段分发到对应的处理器。
     *
     * @param jsonRequest JSON格式的请求字符串
     * @return JSON格式的响应字符串
     */
    public String handleJsonRpcRequest(String jsonRequest) {
        try {
            JsonNode root = objectMapper.readTree(jsonRequest);
            String method = root.has("method") ? root.get("method").asText() : "";
            JsonNode id = root.get("id");
            return switch (method) {
                case "tools/list" -> handleToolsList(id);
                case "tools/call" -> handleToolsCall(id, root.get("params"));
                case "resources/list" -> handleResourcesList(id);
                case "prompts/list" -> handlePromptsList(id);
                case "initialize" -> handleInitialize(id);
                default -> errorResponse(id, -32601, "Method not found: " + method);
            };
        } catch (JsonProcessingException e) {
            log.error("Failed to parse JSON-RPC request", e);
            return errorResponse(null, -32700, "Parse error: " + e.getMessage());
        }
    }

    /** 处理 tools/list 请求：返回所有已注册工具的列表 */
    private String handleToolsList(JsonNode id) {
        ArrayNode toolArray = objectMapper.createArrayNode();
        for (var t : tools.values()) {
            ObjectNode node = objectMapper.createObjectNode()
                    .put("name", t.getName()).put("description", t.getDescription())
                    .put("serverName", t.getServerName());
            if (t.getInputSchema() != null) node.set("inputSchema", objectMapper.valueToTree(t.getInputSchema()));
            toolArray.add(node);
        }
        return successResponse(id, objectMapper.createObjectNode().set("tools", toolArray));
    }

    /**
     * 处理 tools/call 请求：执行指定工具并返回结果。
     *
     * @param id     请求ID
     * @param params 包含工具名称和参数的JSON节点
     * @return JSON-RPC响应
     */
    private String handleToolsCall(JsonNode id, JsonNode params) {
        if (params == null || !params.has("name"))
            return errorResponse(id, -32602, "Invalid params: missing 'name'");
        String toolName = params.get("name").asText();
        Map<String, Object> args = params.has("arguments")
                ? objectMapper.convertValue(params.get("arguments"), Map.class) : Map.of();
        try {
            ToolResult result = executeTool(toolName, args).get();
            ObjectNode content = objectMapper.createObjectNode()
                    .put("type", "text")
                    .put("text", result.isSuccess() ? result.getOutput() : result.getErrorMessage());
            ObjectNode resultNode = objectMapper.createObjectNode()
                    .put("isError", !result.isSuccess());
            resultNode.set("content", objectMapper.createArrayNode().add(content));
            return successResponse(id, resultNode);
        } catch (Exception e) {
            return errorResponse(id, -32000, "Tool execution error: " + e.getMessage());
        }
    }

    /** 处理 resources/list 请求：返回所有已注册资源的列表 */
    private String handleResourcesList(JsonNode id) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (var r : resources.values()) {
            arr.add(objectMapper.createObjectNode()
                    .put("uri", r.getUri()).put("name", r.getName())
                    .put("description", r.getDescription()).put("mimeType", r.getMimeType()));
        }
        return successResponse(id, objectMapper.createObjectNode().set("resources", arr));
    }

    /** 处理 prompts/list 请求：返回所有已注册提示词的列表 */
    private String handlePromptsList(JsonNode id) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (var p : prompts.values()) {
            ObjectNode node = objectMapper.createObjectNode()
                    .put("name", p.getName()).put("description", p.getDescription());
            if (p.getArguments() != null) {
                ArrayNode argsArr = objectMapper.createArrayNode();
                for (var arg : p.getArguments())
                    argsArr.add(objectMapper.createObjectNode()
                            .put("name", arg.getName()).put("description", arg.getDescription())
                            .put("required", arg.isRequired()));
                node.set("arguments", argsArr);
            }
            arr.add(node);
        }
        return successResponse(id, objectMapper.createObjectNode().set("prompts", arr));
    }

    /** 处理 initialize 请求：返回服务器信息、协议版本和能力声明 */
    private String handleInitialize(JsonNode id) {
        ObjectNode caps = objectMapper.createObjectNode()
                .put("tools", objectMapper.createObjectNode().put("listChanged", true).toString())
                .put("resources", objectMapper.createObjectNode().put("subscribe", false).put("listChanged", false).toString())
                .put("prompts", objectMapper.createObjectNode().put("listChanged", false).toString());
        ObjectNode result = objectMapper.createObjectNode()
                .put("protocolVersion", "2024-11-05");
        result.set("serverInfo", objectMapper.createObjectNode().put("name", serverName).put("version", serverVersion));
        result.set("capabilities", caps);
        return successResponse(id, result);
    }

    /**
     * 构建成功的JSON-RPC响应。
     *
     * @param id     请求ID
     * @param result 结果对象
     * @return JSON-RPC响应字符串
     */
    private String successResponse(JsonNode id, JsonNode result) {
        ObjectNode resp = objectMapper.createObjectNode().put("jsonrpc", "2.0");
        if (id != null) resp.set("id", id);
        resp.set("result", result);
        try { return objectMapper.writeValueAsString(resp); }
        catch (JsonProcessingException e) { log.error("Failed to serialize success response", e); return "{}"; }
    }

    /**
     * 构建错误的JSON-RPC响应。
     *
     * @param id      请求ID
     * @param code    错误码
     * @param message 错误消息
     * @return JSON-RPC响应字符串
     */
    private String errorResponse(JsonNode id, int code, String message) {
        ObjectNode err = objectMapper.createObjectNode().put("code", code).put("message", message);
        ObjectNode resp = objectMapper.createObjectNode().put("jsonrpc", "2.0");
        if (id != null) resp.set("id", id);
        resp.set("error", err);
        try { return objectMapper.writeValueAsString(resp); }
        catch (JsonProcessingException e) { log.error("Failed to serialize error response", e); return "{}"; }
    }

    /** 启动STDIO传输：从标准输入读取JSON-RPC请求，将响应写入标准输出 */
    private void startStdioTransport() {
        log.info("STDIO transport initialized - ready for JSON-RPC on stdin");
        stdinReaderThread = new Thread(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while (running.get() && (line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String request = line;
                    executor.submit(() -> {
                        String response = handleJsonRpcRequest(request);
                        synchronized (System.out) { System.out.println(response); System.out.flush(); }
                    });
                }
            } catch (IOException e) {
                if (running.get()) log.error("STDIO read error", e);
            }
        }, "mcp-stdio-reader");
        stdinReaderThread.setDaemon(true);
    }

    /** 启动SSE传输模式（当前为占位实现） */
    private void startSseTransport() {
        log.info("SSE transport initialized - endpoint at /mcp/sse");
    }

    /** 构建工具执行的输出字符串，包含工具名称、描述、参数和服务名称 */
    private String buildToolExecutionOutput(McpToolDescriptor descriptor, Map<String, Object> args) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tool [").append(descriptor.getName()).append("] executed");
        if (descriptor.getDescription() != null && !descriptor.getDescription().isEmpty())
            sb.append(": ").append(descriptor.getDescription());
        if (args != null && !args.isEmpty()) sb.append("\nArguments: ").append(args);
        sb.append("\nServer: ").append(descriptor.getServerName() != null ? descriptor.getServerName() : serverName);
        return sb.toString();
    }
}
