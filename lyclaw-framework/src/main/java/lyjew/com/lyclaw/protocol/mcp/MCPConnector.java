package lyjew.com.lyclaw.protocol.mcp;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.ToolExecutionResult;

/**
 * MCP (Model Context Protocol) 连接器 SPI——Agent 与 MCP Server 之间的标准化接口。
 *
 * <p>MCP 是 2025-2026 年行业标准协议，定义了 AI 模型与外部工具/资源之间的统一交互方式。
 * 实现此接口后，LyClaw 可直接接入全球 200+ 现有的 MCP Server（文件系统、数据库、API 网关等）。
 *
 * <p>默认实现 {@code McpHttpClient} 使用 JSON-RPC over HTTP 与 MCP Server 通信。
 * 用户可替换为 SSE、stdio 等传输方式。
 *
 * @see <a href="https://modelcontextprotocol.io/">MCP 官方规范</a>
 */
public interface MCPConnector {
    /**
     * 连接 MCP Server。
     *
     * @param serverUrl MCP Server 的 URL（如 http://localhost:9000）
     */
    void connect(String serverUrl);

    /**
     * 从 MCP Server 获取可用工具列表。
     *
     * @return MCP Server 提供的工具定义列表
     */
    List<ToolDefinition> listTools();

    /**
     * 通过 MCP 协议调用工具。
     *
     * @param toolName 工具名称
     * @param args 工具参数
     * @return 工具执行结果
     */
    ToolExecutionResult callTool(String toolName, Map<String, Object> args);

    /**
     * 获取 MCP Server 提供的资源列表。
     */
    default List<McpResourceDescriptor> listResources() { return Collections.emptyList(); }

    /**
     * 获取 MCP Server 提供的提示词模板列表。
     */
    default List<McpPromptDescriptor> listPrompts() { return Collections.emptyList(); }

    /** 关闭与 MCP Server 的连接 */
    void disconnect();

    /** 检查是否已连接 */
    default boolean isConnected() { return false; }
}
