package lyjew.com.lyclaw.protocol.mcp;

import lyjew.com.lyclaw.tool.ToolExecutionResult;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * MCP（Model Context Protocol）服务端接口，定义 MCP 服务器的核心操作。
 *
 * <p>MCP 服务器是工具、资源和提示的提供方。AI 模型通过 MCP 客户端连接
 * 到服务器来获取额外的能力和上下文。该接口定义了服务器的生命周期管理
 * （启动/停止）、资源注册（工具/资源/提示），以及工具执行等操作。</p>
 *
 * <p>典型使用流程：</p>
 * <ol>
 *   <li>调用 {@link #start()} 启动服务器</li>
 *   <li>通过 {@link #registerTool(McpToolDescriptor)} 等方法注册能力</li>
 *   <li>客户端连接后通过 {@link #listTools()} 查询可用工具</li>
 *   <li>通过 {@link #executeTool(String, Map)} 执行工具</li>
 *   <li>调用 {@link #stop()} 停止服务器</li>
 * </ol>
 */
public interface McpServer {

    /**
     * 启动 MCP 服务器，开始监听客户端连接。
     */
    void start();

    /**
     * 停止 MCP 服务器，释放相关资源。
     */
    void stop();

    /**
     * 向服务器注册一个工具，使其可被客户端发现和调用。
     *
     * @param tool 工具描述符，包含名称、描述和输入 schema
     */
    void registerTool(McpToolDescriptor tool);

    /**
     * 向服务器注册一个资源，使其可被客户端读取。
     *
     * @param resource 资源描述符，包含 URI、名称和 MIME 类型
     */
    void registerResource(McpResourceDescriptor resource);

    /**
     * 向服务器注册一个提示模板，使其可被客户端使用。
     *
     * @param prompt 提示描述符，包含名称、描述和参数定义
     */
    void registerPrompt(McpPromptDescriptor prompt);

    /**
     * 获取服务器的名称。
     *
     * @return 服务器名称
     */
    String getServerName();

    /**
     * 获取服务器的版本号。
     *
     * @return 版本号字符串
     */
    String getServerVersion();

    /**
     * 获取服务器使用的传输类型。
     *
     * @return 传输类型枚举值
     */
    McpTransportType getTransportType();

    /**
     * 列出服务器上所有已注册的工具。
     *
     * @return 工具描述符集合
     */
    Set<McpToolDescriptor> listTools();

    /**
     * 执行指定名称的工具并返回结果。
     *
     * @param toolName 工具名称
     * @param args     工具参数字典
     * @return 包含工具执行结果的 CompletableFuture
     */
    CompletableFuture<ToolExecutionResult> executeTool(String toolName, Map<String, Object> args);
}
