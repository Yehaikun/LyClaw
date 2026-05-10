package lyjew.com.lyclaw.protocol.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.action.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class McpClientImpl implements McpClient {

    private static class ServerConnection {
        final String serverKey;
        final String serverName;
        final String transportType;
        final Process process;
        final BufferedWriter stdinWriter;
        final BufferedReader stdoutReader;
        final String sseEndpoint;
        final Map<String, McpToolDescriptor> tools = new ConcurrentHashMap<>();
        final AtomicLong requestIdCounter = new AtomicLong(1);
        volatile boolean initialized;

        ServerConnection(String serverKey, String serverName, String transportType,
                         Process process, BufferedWriter stdinWriter, BufferedReader stdoutReader,
                         String sseEndpoint) {
            this.serverKey = serverKey;
            this.serverName = serverName;
            this.transportType = transportType;
            this.process = process;
            this.stdinWriter = stdinWriter;
            this.stdoutReader = stdoutReader;
            this.sseEndpoint = sseEndpoint;
        }
    }

    private final Map<String, ServerConnection> connections = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public CompletableFuture<Void> connect(String serverCommand, List<String> args) {
        String key = deriveServerKey(serverCommand, args);
        if (connections.containsKey(key)) {
            log.warn("Already connected: {}", key);
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String> cmd = new ArrayList<>(); cmd.add(serverCommand);
                if (args != null) cmd.addAll(args);
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                var conn = new ServerConnection(key, serverCommand, "stdio", p,
                        new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)),
                        new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)),
                        null);
                connections.put(key, conn);
                initializeServer(key);
                log.info("Connected via stdio: {}", key);
                return null;
            } catch (IOException e) {
                log.error("Failed to connect: {}", key, e);
                throw new CompletionException("Failed to connect: " + e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> connectViaSse(String endpointUrl) {
        String key = "sse-" + endpointUrl.hashCode();
        if (connections.containsKey(key)) {
            log.warn("Already connected via SSE: {}", endpointUrl);
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {
            var conn = new ServerConnection(key, endpointUrl, "sse", null, null, null, endpointUrl);
            conn.initialized = true;
            connections.put(key, conn);
            log.info("SSE connection registered: {}", key);
            return null;
        }, executor);
    }

    @Override
    public void disconnect() {
        log.info("Disconnecting {} server(s)", connections.size());
        for (var conn : connections.values()) {
            try {
                if ("stdio".equals(conn.transportType) && conn.process != null) {
                    conn.process.destroy();
                    conn.process.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (conn.process != null) conn.process.destroyForcibly();
            }
        }
        connections.clear();
    }

    @Override
    public List<McpToolDescriptor> discoverTools() {
        List<McpToolDescriptor> all = new ArrayList<>();
        for (var conn : connections.values()) {
            if (!conn.initialized) continue;
            try {
                List<McpToolDescriptor> tools = discoverToolsFromServer(conn);
                conn.tools.clear();
                tools.forEach(t -> conn.tools.put(t.getName(), t));
                all.addAll(tools);
                log.info("Discovered {} tools from {}", tools.size(), conn.serverKey);
            } catch (Exception e) {
                log.error("Tool discovery failed for {}", conn.serverKey, e);
            }
        }
        return all;
    }

    private List<McpToolDescriptor> discoverToolsFromServer(ServerConnection conn) throws Exception {
        ObjectNode request = buildRequest("tools/list", null, conn);
        String response = sendJsonRpcRequest(request, conn);
        JsonNode root = objectMapper.readTree(response);
        if (root.has("error")) throw new IOException("MCP error: " + root.get("error").get("message").asText());
        List<McpToolDescriptor> tools = new ArrayList<>();
        JsonNode arr = root.path("result").path("tools");
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                tools.add(McpToolDescriptor.builder()
                        .name(n.path("name").asText())
                        .description(n.path("description").asText(""))
                        .inputSchema(n.has("inputSchema") ? objectMapper.convertValue(n.get("inputSchema"), Map.class) : Map.of())
                        .serverName(conn.serverName).build());
            }
        }
        return tools;
    }

    @Override
    public CompletableFuture<ToolResult> callTool(String toolName, Map<String, Object> arguments) {
        if (connections.isEmpty())
            return CompletableFuture.completedFuture(ToolResult.builder().toolName(toolName).success(false)
                    .errorMessage("No MCP servers connected").output(null).durationMs(0).build());

        ServerConnection target = connections.values().stream()
                .filter(c -> c.tools.containsKey(toolName) || c.initialized).findFirst().orElse(null);
        if (target == null)
            return CompletableFuture.completedFuture(ToolResult.builder().toolName(toolName).success(false)
                    .errorMessage("No connected server has tool: " + toolName).output(null).durationMs(0).build());

        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            try {
                Map<String, Object> params = Map.of("name", toolName,
                        "arguments", arguments != null ? arguments : Map.of());
                ObjectNode request = buildRequest("tools/call", params, target);
                String response = sendJsonRpcRequest(request, target);
                JsonNode root = objectMapper.readTree(response);
                if (root.has("error")) {
                    return ToolResult.builder().toolName(toolName).success(false)
                            .errorMessage(root.get("error").get("message").asText())
                            .output(null).durationMs(System.currentTimeMillis() - start).build();
                }
                StringBuilder output = new StringBuilder();
                for (JsonNode block : root.path("result").path("content"))
                    if ("text".equals(block.path("type").asText()))
                        output.append(block.path("text").asText());
                return ToolResult.builder().toolName(toolName).success(true)
                        .output(output.toString()).durationMs(System.currentTimeMillis() - start)
                        .metadata(Map.of("serverKey", target.serverKey)).build();
            } catch (Exception e) {
                log.error("Tool call failed: {}", toolName, e);
                return ToolResult.builder().toolName(toolName).success(false)
                        .errorMessage("Tool call failed: " + e.getMessage())
                        .output(null).durationMs(System.currentTimeMillis() - start).build();
            }
        }, executor);
    }

    @Override public Set<String> listConnectedServers() { return Set.copyOf(connections.keySet()); }
    @Override public boolean isConnected(String key) {
        var c = connections.get(key); return c != null && c.initialized;
    }
    public int getConnectionCount() { return connections.size(); }

    public void initializeServer(String serverKey) throws IOException {
        ServerConnection conn = connections.get(serverKey);
        if (conn == null) throw new IOException("No connection: " + serverKey);
        ObjectNode params = objectMapper.createObjectNode()
                .put("protocolVersion", "2024-11-05");
        params.set("capabilities", objectMapper.createObjectNode());
        params.set("clientInfo", objectMapper.createObjectNode()
                .put("name", "lyclaw-mcp-client").put("version", "0.0.1"));
        ObjectNode request = buildRequest("initialize", params, conn);
        String response = sendJsonRpcRequest(request, conn);
        JsonNode root = objectMapper.readTree(response);
        if (root.has("error")) throw new IOException("Initialize failed: " + root.get("error").get("message").asText());
        conn.initialized = true;
        log.info("Server initialized: {} (protocol: {})", serverKey,
                root.path("result").path("protocolVersion").asText("unknown"));
    }

    private ObjectNode buildRequest(String method, Object params, ServerConnection conn) {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("jsonrpc", "2.0").put("method", method).put("id", conn.requestIdCounter.getAndIncrement());
        if (params != null) req.set("params", objectMapper.valueToTree(params));
        return req;
    }

    private String sendJsonRpcRequest(ObjectNode request, ServerConnection conn) throws IOException {
        return "stdio".equals(conn.transportType) ? sendStdioRequest(request, conn) : sendSseRequest(request, conn);
    }

    private String sendStdioRequest(ObjectNode request, ServerConnection conn) throws IOException {
        if (conn.stdinWriter == null || conn.stdoutReader == null)
            throw new IOException("Stdio streams not available: " + conn.serverKey);
        synchronized (conn) {
            String json = objectMapper.writeValueAsString(request);
            conn.stdinWriter.write(json); conn.stdinWriter.newLine(); conn.stdinWriter.flush();
            String response = conn.stdoutReader.readLine();
            if (response == null) throw new IOException("Server closed stdout: " + conn.serverKey);
            return response;
        }
    }

    private String sendSseRequest(ObjectNode request, ServerConnection conn) throws IOException {
        String method = request.get("method").asText();
        log.info("SSE request: {} to {}", method, conn.sseEndpoint);
        if ("tools/list".equals(method)) return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}";
        if ("tools/call".equals(method)) {
            ObjectNode resp = objectMapper.createObjectNode();
            resp.put("jsonrpc", "2.0").put("id", request.get("id").asLong());
            resp.putObject("result").putArray("content").addObject()
                    .put("type", "text").put("text", "SSE tool call result (simulated)");
            return objectMapper.writeValueAsString(resp);
        }
        if ("initialize".equals(method))
            return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\",\"serverInfo\":{\"name\":\"sse-server\",\"version\":\"1.0\"},\"capabilities\":{}}}";
        return "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id").asLong() + ",\"result\":{}}";
    }

    private String deriveServerKey(String cmd, List<String> args) {
        StringBuilder sb = new StringBuilder(cmd);
        if (args != null) args.forEach(a -> sb.append(':').append(a));
        return sb.toString();
    }
}
