package lyjew.com.lyclaw.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * OpenAI 兼容 API 的响应体 DTO。
 *
 * <p>该类映射 OpenAI Chat Completions API 的响应 JSON 结构。
 * 使用 {@link JsonIgnoreProperties#ignoreUnknown} 忽略 API 新增的未知字段，
 * 确保向后兼容。</p>
 *
 * <p>内部类：
 * <ul>
 *   <li>{@link Choice} - 模型回复选项（通常取第一个）</li>
 *   <li>{@link ResponseMessage} - 回复消息内容</li>
 *   <li>{@link ToolCall} - 工具调用请求</li>
 *   <li>{@link FunctionCall} - 函数调用详情</li>
 *   <li>{@link OpenAIUsage} - Token 用量统计</li>
 * </ul>
 * </p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAIResponse {

    /** 响应唯一标识 */
    private String id;
    /** 对象类型，通常为 "chat.completion" */
    private String object;
    /** 创建时间戳（Unix 秒） */
    private long created;
    /** 实际使用的模型名称 */
    private String model;
    /** 回复选项列表 */
    private List<Choice> choices;
    /** Token 用量信息 */
    private OpenAIUsage usage;

    /**
     * 单个回复选项。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        /** 选项序号 */
        private int index;
        /** 回复消息 */
        private ResponseMessage message;
        /** 结束原因：stop/length/tool_calls/content_filter 等 */
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    /**
     * 回复消息。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResponseMessage {
        /** 角色，通常为 "assistant" */
        private String role;
        /** 消息文本内容 */
        private String content;
        /** 工具调用列表（LLM 请求调用工具时非空） */
        @JsonProperty("tool_calls")
        private List<ToolCall> toolCalls;
    }

    /**
     * 工具调用请求。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolCall {
        /** 工具调用 ID */
        private String id;
        /** 类型，固定 "function" */
        private String type;
        /** 函数调用详情 */
        private FunctionCall function;
    }

    /**
     * 函数调用详情。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FunctionCall {
        /** 函数名称 */
        private String name;
        /** JSON 格式的参数 */
        private String arguments;
    }

    /**
     * Token 用量统计。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OpenAIUsage {
        /** 提示词消耗的 Token 数 */
        @JsonProperty("prompt_tokens")
        private int promptTokens;
        /** 补全消耗的 Token 数 */
        @JsonProperty("completion_tokens")
        private int completionTokens;
        /** 总 Token 数 */
        @JsonProperty("total_tokens")
        private int totalTokens;
    }

    /**
     * 获取第一个（通常也是唯一的）回复选项。
     *
     * @return 第一个 Choice，choices 为空时返回 null
     */
    public Choice getFirstChoice() {
        return choices != null && !choices.isEmpty() ? choices.get(0) : null;
    }

    /**
     * 判断响应是否成功。
     *
     * @return 第一个 choice 的 finish_reason 为 "stop" 时返回 true
     */
    public boolean isSuccess() {
        Choice first = getFirstChoice();
        return first != null && "stop".equals(first.getFinishReason());
    }
}
