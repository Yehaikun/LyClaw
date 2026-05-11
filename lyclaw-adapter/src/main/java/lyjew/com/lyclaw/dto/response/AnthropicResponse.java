package lyjew.com.lyclaw.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Anthropic Messages API 的响应体 DTO。
 *
 * <p>映射 Anthropic 格式的 /v1/messages 端点返回的 JSON 结构。
 * 与 OpenAI 格式的主要区别：</p>
 * <ul>
 *   <li>content 是数组，每个元素带有 type 标记（如 "text"、"thinking"）</li>
 *   <li>使用 stop_reason 而非 finish_reason 表示完成原因</li>
 *   <li>Token 用量字段名为 input_tokens/output_tokens</li>
 *   <li>包含 base_resp 业务状态码（MiniMax 等厂商扩展）</li>
 * </ul>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnthropicResponse {

    /** 响应唯一标识 */
    private String id;
    /** 对象类型，Anthropic 格式为 "message" */
    private String type;
    /** 角色（通常为 "assistant"） */
    private String role;
    /** 实际使用的模型名称 */
    private String model;
    /** content 数组，每个元素包含 type 和对应内容 */
    private List<ContentBlock> content;
    /** Token 用量统计 */
    private AnthropicUsage usage;
    /** 完成原因，对应 Anthropic 的 stop_reason 字段 */
    @JsonProperty("stop_reason")
    private String stopReason;
    /** 业务状态码（MiniMax 等厂商扩展字段） */
    @JsonProperty("base_resp")
    private BaseResp baseResp;

    /**
     * content 数组中的单个内容块。
     *
     * <p>可能包含 text 块（普通文本）或 thinking 块（模型思考内容），
     * 通过 type 字段区分。</p>
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContentBlock {
        /** 内容块类型：text 或 thinking */
        private String type;
        /** 思考内容（thinking 类型块时使用） */
        private String thinking;
        /** 思考内容签名 */
        private String signature;
        /** 文本内容（text 类型块时使用） */
        private String text;
    }

    /**
     * Anthropic 格式的 Token 用量统计。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AnthropicUsage {
        /** 输入 Token 数 */
        @JsonProperty("input_tokens")
        private int inputTokens;
        /** 输出 Token 数 */
        @JsonProperty("output_tokens")
        private int outputTokens;
    }

    /**
     * 业务层响应状态（MiniMax 等厂商扩展）。
     *
     * <p>statusCode 为 0 表示业务层面成功。</p>
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BaseResp {
        /** 业务状态码，0 表示成功 */
        @JsonProperty("status_code")
        private int statusCode;
        /** 业务状态消息 */
        @JsonProperty("status_msg")
        private String statusMsg;

        /**
         * 判断业务状态码是否表示成功。
         *
         * @return true 表示 statusCode 为 0
         */
        public boolean isSuccess() {
            return statusCode == 0;
        }
    }
}
