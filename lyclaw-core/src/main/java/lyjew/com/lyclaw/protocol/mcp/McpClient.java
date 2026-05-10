package lyjew.com.lyclaw.protocol.mcp;

import lyjew.com.lyclaw.action.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface McpClient {

    CompletableFuture<Void> connect(String serverCommand, List<String> args);
    CompletableFuture<Void> connectViaSse(String endpointUrl);
    void disconnect();
    List<McpToolDescriptor> discoverTools();
    CompletableFuture<ToolResult> callTool(String toolName, Map<String, Object> arguments);
    Set<String> listConnectedServers();
    boolean isConnected(String serverKey);
}
