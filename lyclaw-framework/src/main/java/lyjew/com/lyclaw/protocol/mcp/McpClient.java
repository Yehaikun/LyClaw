package lyjew.com.lyclaw.protocol.mcp;

import lyjew.com.lyclaw.action.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * MCP（Model Context Protocol）客户端接口，定义与 MCP 服务端交互的核心操作。
 *
 * <p>MCP 客户端负责连接 MCP 服务器（通过标准 I/O 或 SSE），发现其提供的工具
 * （Tools）、资源（Resources）和提示（Prompts），并调用这些工具来扩展 AI 模型的
 * 能力边界。所有远程操作均为异步，返回 {@link CompletableFuture}。</p>
 *
 * <p>典型使用流程：</p>
 * <ol>
 *   <li>调用 {@link #connect(String, List)} 或 {@link #connectViaSse(String)} 建立连接</li>
 *   <li>调用 {@link #discoverTools()} 获取可用工具列表</li>
 *   <li>调用 {@link #callTool(String, Map)} 执行工具调用</li>
 *   <li>调用 {@link #disconnect()} 断开连接</li>
 * </ol>
 */
public interface McpClient {

    /**
     * 通过命令行启动 MCP 服务器进程并建立标准 I/O 连接。
     *
     * @param serverCommand 服务器启动命令，如 "python" 或 "node"
     * @param args          命令行参数列表
     * @return 连接完成后的 CompletableFuture
     */
    CompletableFuture<Void> connect(String serverCommand, List<String> args);

    /**
     * 通过 SSE（Server-Sent Events）端点 URL 建立远程连接。
     *
     * @param endpointUrl SSE 端点 URL
     * @return 连接完成后的 CompletableFuture
     */
    CompletableFuture<Void> connectViaSse(String endpointUrl);

    /**
     * 断开与 MCP 服务器的连接，释放相关资源。
     */
    void disconnect();

    /**
     * 发现并返回当前连接服务器上所有可用的工具描述符。
     *
     * @return 工具描述符列表，包含工具名称、描述和输入 schema
     */
    List<McpToolDescriptor> discoverTools();

    /**
     * 调用指定名称的远程工具，并传入参数。
     *
     * @param toolName  工具名称
     * @param arguments 工具参数映射
     * @return 包含工具执行结果的 CompletableFuture
     */
    CompletableFuture<ToolResult> callTool(String toolName, Map<String, Object> arguments);

    /**
     * 列出当前已连接的所有服务器的唯一标识键。
     *
     * @return 已连接服务器的标识键集合
     */
    Set<String> listConnectedServers();

    /**
     * 检查指定键对应的服务器是否处于已连接状态。
     *
     * @param serverKey 服务器的唯一标识键
     * @return true 表示已连接，false 表示未连接
     */
    boolean isConnected(String serverKey);
}
