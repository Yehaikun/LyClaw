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

    @Override
    public void start() {
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

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        log.info("Stopping server...");
        if (subprocess != null && subprocess.isAlive()) {
            subprocess.destroy();
            try { subprocess.waitFor(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); subprocess.destroyForcibly(); }
        }
        if (stdinReaderThread != null && stdinReaderThread.isAlive()) stdinReaderThread.interrupt();
        tools.clear(); resources.clear(); prompts.clear();
        log.info("Stopped");
    }

    public boolean isRunning() { return running.get(); }

    @Override
    public void registerTool(McpToolDescriptor tool) {
        tools.put(tool.getName(), tool);
        log.info("Registered tool: {} (server: {})", tool.getName(), tool.getServerName());
    }

    @Override
    public void registerResource(McpResourceDescriptor resource) {
        resources.put(resource.getUri(), resource);
        log.info("Registered resource: {} ({})", resource.getUri(), resource.getName());
    }

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

    private String handleResourcesList(JsonNode id) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (var r : resources.values()) {
            arr.add(objectMapper.createObjectNode()
                    .put("uri", r.getUri()).put("name", r.getName())
                    .put("description", r.getDescription()).put("mimeType", r.getMimeType()));
        }
        return successResponse(id, objectMapper.createObjectNode().set("resources", arr));
    }

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

    private String successResponse(JsonNode id, JsonNode result) {
        ObjectNode resp = objectMapper.createObjectNode().put("jsonrpc", "2.0");
        if (id != null) resp.set("id", id);
        resp.set("result", result);
        try { return objectMapper.writeValueAsString(resp); }
        catch (JsonProcessingException e) { log.error("Failed to serialize success response", e); return "{}"; }
    }

    private String errorResponse(JsonNode id, int code, String message) {
        ObjectNode err = objectMapper.createObjectNode().put("code", code).put("message", message);
        ObjectNode resp = objectMapper.createObjectNode().put("jsonrpc", "2.0");
        if (id != null) resp.set("id", id);
        resp.set("error", err);
        try { return objectMapper.writeValueAsString(resp); }
        catch (JsonProcessingException e) { log.error("Failed to serialize error response", e); return "{}"; }
    }

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

    private void startSseTransport() {
        log.info("SSE transport initialized - endpoint at /mcp/sse");
    }

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
