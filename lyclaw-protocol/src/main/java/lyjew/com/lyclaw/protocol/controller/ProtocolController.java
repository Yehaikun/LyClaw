package lyjew.com.lyclaw.protocol.controller;

import lyjew.com.lyclaw.protocol.mcp.McpClient;
import lyjew.com.lyclaw.protocol.mcp.McpToolDescriptor;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/protocol")
public class ProtocolController {

    private final McpClient mcpClient;

    public ProtocolController(McpClient mcpClient) {
        this.mcpClient = mcpClient;
    }

    @PostMapping("/mcp/discover")
    public List<McpToolDescriptor> discoverTools(@RequestParam String serverCommand) {
        mcpClient.connect(serverCommand, Collections.emptyList());
        List<McpToolDescriptor> tools = mcpClient.discoverTools();
        mcpClient.disconnect();
        return tools;
    }

    @PostMapping("/model/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> request) {
        return Map.of(
                "status", "ok",
                "message", "chat endpoint stub",
                "input", request);
    }

    @GetMapping("/a2a/card")
    public Map<String, Object> agentCard() {
        return Map.of(
                "agentId", "lyclaw-protocol-agent",
                "name", "LyClaw Protocol Agent",
                "description", "Protocol service agent for MCP and A2A",
                "version", "0.0.1",
                "url", "http://localhost:8086");
    }
}
