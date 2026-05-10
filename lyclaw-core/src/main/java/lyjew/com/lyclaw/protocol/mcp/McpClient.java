package lyjew.com.lyclaw.protocol.mcp;

import lyjew.com.lyclaw.action.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * MCP Client 接口 —— 连接外部 MCP 服务, 发现并调用其工具。
 *
 * @since 2.0
 */
public interface McpClient {

    CompletableFuture<Void> connect(String serverCommand, List<String> args);

    CompletableFuture<Void> connectViaSse(String endpointUrl);

    void disconnect();

    List<McpToolDescriptor> discoverTools();

    CompletableFuture<ToolResult> callTool(String toolName, Map<String, Object> arguments);

    Set<String> listConnectedServers();

    boolean isConnected(String serverKey);
}
