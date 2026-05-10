package lyjew.com.lyclaw.protocol.mcp;

import lyjew.com.lyclaw.action.tool.ToolResult;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * MCP Server 接口 —— 将 LyClaw 的工具暴露为 MCP 服务。
 *
 * <p>支持 stdio/SSE/WebSocket 三种传输方式。</p>
 *
 * @since 2.0
 */
public interface McpServer {

    void start();
    void stop();

    void registerTool(McpToolDescriptor tool);
    void registerResource(McpResourceDescriptor resource);
    void registerPrompt(McpPromptDescriptor prompt);

    String getServerName();
    String getServerVersion();
    McpTransportType getTransportType();

    Set<McpToolDescriptor> listTools();
    CompletableFuture<ToolResult> executeTool(String toolName, Map<String, Object> args);
}
