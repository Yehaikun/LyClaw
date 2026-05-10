package lyjew.com.lyclaw.protocol.mcp;

import lyjew.com.lyclaw.action.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class McpServerImpl implements McpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServerImpl.class);

    private final Map<String, McpToolDescriptor> tools = new ConcurrentHashMap<>();
    private final Map<String, McpResourceDescriptor> resources = new ConcurrentHashMap<>();
    private final Map<String, McpPromptDescriptor> prompts = new ConcurrentHashMap<>();

    @Override
    public void start() {
        log.info("McpServer started");
    }

    @Override
    public void stop() {
        log.info("McpServer stopped");
    }

    @Override
    public void registerTool(McpToolDescriptor tool) {
        tools.put(tool.getName(), tool);
        log.info("Registered tool: {}", tool.getName());
    }

    @Override
    public void registerResource(McpResourceDescriptor resource) {
        resources.put(resource.getUri(), resource);
        log.info("Registered resource: {}", resource.getUri());
    }

    @Override
    public void registerPrompt(McpPromptDescriptor prompt) {
        prompts.put(prompt.getName(), prompt);
        log.info("Registered prompt: {}", prompt.getName());
    }

    @Override
    public String getServerName() {
        return "lyclaw-mcp-server";
    }

    @Override
    public String getServerVersion() {
        return "0.0.1";
    }

    @Override
    public McpTransportType getTransportType() {
        return McpTransportType.STDIO;
    }

    @Override
    public Set<McpToolDescriptor> listTools() {
        return Set.copyOf(tools.values());
    }

    @Override
    public CompletableFuture<ToolResult> executeTool(String toolName, Map<String, Object> args) {
        return CompletableFuture.completedFuture(
                ToolResult.builder()
                        .toolName(toolName)
                        .success(true)
                        .output("stub output for tool: " + toolName)
                        .durationMs(0)
                        .build()
        );
    }
}
