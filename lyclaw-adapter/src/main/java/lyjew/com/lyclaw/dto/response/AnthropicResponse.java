package lyjew.com.lyclaw.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Anthropic 格式的 API 响应体
 * MiniMax 和 DeepSeek Anthropic 格式都使用这个结构
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnthropicResponse {

    /** 消息唯一 ID */
    private String id;

    /** 对象类型，固定 "message" */
    private String type;

    /** 角色，固定 "assistant" */
    private String role;

    /** 使用的模型名 */
    private String model;

    /**
     * 响应内容块列表
     * 每个元素可能是 thinking 块或 text 块
     */
    private List<ContentBlock> content;

    /** Token 用量 */
    private AnthropicUsage usage;

    /** 停止原因：end_turn / max_tokens / stop_sequence */
    @JsonProperty("stop_reason")
    private String stopReason;

    /** 业务状态（MiniMax 特有） */
    @JsonProperty("base_resp")
    private BaseResp baseResp;

    // ==================== 内部类 ====================

    /**
     * 内容块——可能包含 thinking（思考）或 text（文本回复）
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContentBlock {
        /** 块类型：thinking / text */
        private String type;

        /** 思考过程的文本（type="thinking" 时有值） */
        private String thinking;

        /** 思考签名（type="thinking" 时有值） */
        private String signature;

        /** 回复文本（type="text" 时有值） */
        private String text;
    }

    /**
     * Anthropic 格式的 Token 用量
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AnthropicUsage {
        @JsonProperty("input_tokens")
        private int inputTokens;

        @JsonProperty("output_tokens")
        private int outputTokens;
    }

    /**
     * MiniMax 特有的业务状态（只有 MiniMax 会返回这个）
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BaseResp {
        @JsonProperty("status_code")
        private int statusCode;

        @JsonProperty("status_msg")
        private String statusMsg;

        /** 是否成功 */
        public boolean isSuccess() {
            return statusCode == 0;
        }
    }
}