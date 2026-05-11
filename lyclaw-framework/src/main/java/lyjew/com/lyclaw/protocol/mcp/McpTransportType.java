package lyjew.com.lyclaw.protocol.mcp;

/**
 * MCP 协议支持的传输类型枚举。
 *
 * <p>MCP 协议支持多种传输方式，以适应不同的部署场景：</p>
 * <ul>
 *   <li>{@code STDIO} - 标准输入/输出流，适用于本地子进程通信。客户端通过
 *       启动服务器命令行进程并在进程间通过 stdin/stdout 交换 JSON-RPC 消息</li>
 *   <li>{@code SSE} - Server-Sent Events，适用于远程 HTTP 通信。服务器通过
 *       HTTP SSE 端点推送事件，客户端通过 HTTP POST 发送请求</li>
 *   <li>{@code WEBSOCKET} - WebSocket 全双工通信，适用于需要双向实时通信的场景</li>
 * </ul>
 */
public enum McpTransportType {
    /** 标准输入/输出流传输 */
    STDIO,
    /** Server-Sent Events 传输 */
    SSE,
    /** WebSocket 传输 */
    WEBSOCKET
}
