package lyjew.com.lyclaw.protocol.mcp;

import lyjew.com.lyclaw.action.tool.ToolResult;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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
