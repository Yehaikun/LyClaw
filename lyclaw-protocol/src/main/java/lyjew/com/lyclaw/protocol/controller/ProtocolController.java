package lyjew.com.lyclaw.protocol.controller;

import lyjew.com.lyclaw.protocol.mcp.McpClient;
import lyjew.com.lyclaw.protocol.mcp.McpToolDescriptor;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 协议服务的REST控制器，提供MCP工具发现、模型对话和A2A Agent卡片等接口。
 * <p>
 * 当前大部分接口为占位(stub)实现，后续需要对接真实的MCP/A2A协议。
 * </p>
 */
@RestController
@RequestMapping("/api/protocol")
public class ProtocolController {

    private final McpClient mcpClient;

    public ProtocolController(McpClient mcpClient) {
        this.mcpClient = mcpClient;
    }

    /**
     * MCP工具发现接口：连接指定的MCP服务器，发现工具后断开连接。
     *
     * @param serverCommand 要连接的MCP服务器命令
     * @return 发现的工具描述符列表
     */
    @PostMapping("/mcp/discover")
    public List<McpToolDescriptor> discoverTools(@RequestParam String serverCommand) {
        mcpClient.connect(serverCommand, Collections.emptyList());
        List<McpToolDescriptor> tools = mcpClient.discoverTools();
        mcpClient.disconnect();
        return tools;
    }

    /**
     * 模型对话占位接口。
     *
     * @param request 对话请求体
     * @return 占位响应
     */
    @PostMapping("/model/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> request) {
        return Map.of(
                "status", "ok",
                "message", "chat endpoint stub",
                "input", request);
    }

    /**
     * A2A Agent卡片接口。
     *
     * @return 当前协议服务的Agent卡片信息
     */
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
