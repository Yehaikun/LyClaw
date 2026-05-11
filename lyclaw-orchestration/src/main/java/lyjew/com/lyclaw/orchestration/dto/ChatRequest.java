package lyjew.com.lyclaw.orchestration.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 编排层聊天请求 DTO。
 *
 * 用于接收客户端发来的 HTTP 请求体。消息以 List&lt;Map&gt; 的松散格式
 * 接收，由控制器转换为内部 domain 层的 ChatRequest 模型。
 */
@Data
public class ChatRequest {

    /** 会话 ID，可选，不提供时自动创建新会话 */
    private String sessionId;
    /** 消息列表，每条消息为包含 role 和 content 的键值对 */
    private List<Map<String, String>> messages;
    /** 是否使用 SSE 流式响应 */
    private boolean stream;
}
