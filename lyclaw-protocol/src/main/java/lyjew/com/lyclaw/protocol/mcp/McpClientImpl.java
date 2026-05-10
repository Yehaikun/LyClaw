package lyjew.com.lyclaw.protocol.mcp;

import lyjew.com.lyclaw.action.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Component
public class McpClientImpl implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpClientImpl.class);

    @Override
    public CompletableFuture<Void> connect(String serverCommand, List<String> args) {
        log.info("MCP client connecting to: {}", serverCommand);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> connectViaSse(String endpointUrl) {
        log.info("MCP client connecting via SSE to: {}", endpointUrl);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void disconnect() {
        log.info("MCP client disconnected");
    }

    @Override
    public List<McpToolDescriptor> discoverTools() {
        return Collections.emptyList();
    }

    @Override
    public CompletableFuture<ToolResult> callTool(String toolName, Map<String, Object> arguments) {
        return CompletableFuture.completedFuture(
                ToolResult.builder()
                        .toolName(toolName)
                        .success(true)
                        .output("stub callTool result")
                        .durationMs(0)
                        .build()
        );
    }

    @Override
    public Set<String> listConnectedServers() {
        return Collections.emptySet();
    }

    @Override
    public boolean isConnected(String serverKey) {
        return false;
    }
}
