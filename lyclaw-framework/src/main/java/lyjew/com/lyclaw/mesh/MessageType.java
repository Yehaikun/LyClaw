package lyjew.com.lyclaw.mesh;

/**
 * Agent 消息类型 —— 定义 AgentMesh 中所有消息的语义类别。
 *
 * <p>整个 Agent Mesh 的消息协议基于此枚举：
 * <ul>
 *   <li>REQUEST —— 请求（期望 RESPONSE）</li>
 *   <li>RESPONSE —— 对 REQUEST 的回复</li>
 *   <li>STREAM —— 流式数据块</li>
 *   <li>PROGRESS —— 进度更新</li>
 *   <li>EVENT —— 事件通知（不期望回复）</li>
 *   <li>ERROR —— 错误消息</li>
 *   <li>CANCEL —— 取消请求</li>
 * </ul>
 */
public enum MessageType {
    REQUEST,
    RESPONSE,
    STREAM,
    PROGRESS,
    EVENT,
    ERROR,
    CANCEL
}
