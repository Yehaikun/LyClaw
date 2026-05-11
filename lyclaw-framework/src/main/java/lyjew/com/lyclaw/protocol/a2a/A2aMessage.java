package lyjew.com.lyclaw.protocol.a2a;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * A2A 协议中的消息实体，表示代理间的一次通信。
 *
 * <p>Message 是 A2A 协议中代理之间交换信息的基本单元。每条消息包含发送方和
 * 接收方的标识（fromAgentId / toAgentId），消息类型（type）用于区分任务请求、
 * 状态查询、取消请求等不同语义，以及消息载荷（content）和扩展元数据。</p>
 *
 * <p>消息类型由 {@link A2aMessageType} 枚举定义，涵盖了 A2A 协议定义的所有
 * 标准消息类型。</p>
 */
@Data
@Builder
public class A2aMessage {
    /** 消息的唯一标识符 */
    private String messageId;
    /** 发送方代理的 ID */
    private String fromAgentId;
    /** 接收方代理的 ID */
    private String toAgentId;
    /** 消息内容，通常为 JSON 格式的序列化数据 */
    private String content;
    /** 消息类型，用于区分不同的通信语义 */
    private A2aMessageType type;
    /** 扩展元数据，用于携带与消息相关的自定义属性 */
    private Map<String, Object> metadata;
    /** 消息的创建时间戳（毫秒） */
    private long timestamp;
}
