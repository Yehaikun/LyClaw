package lyjew.com.lyclaw.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * OpenAI 格式的 API 响应体
 * DeepSeek OpenAI 格式使用这个结构
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAIResponse {

    /** 请求唯一 ID */
    private String id;

    /** 对象类型，固定 "chat.completion" */
    private String object;

    /** 创建时间的 Unix 时间戳（秒） */
    private long created;

    /** 使用的模型名 */
    private String model;

    /** 回复选项列表（通常只有一个） */
    private List<Choice> choices;

    /** Token 用量 */
    private OpenAIUsage usage;

    // ==================== 内部类 ====================

    /**
     * 一个回复选项
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        /** 选项索引 */
        private int index;

        /** 回复消息 */
        private ResponseMessage message;

        /** 停止原因：stop / length / tool_calls / content_filter */
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    /**
     * 回复消息内容
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResponseMessage {
        /** 角色，固定 "assistant" */
        private String role;

        /**
         * 回复文本——注意和 Anthropic 的 content 不同，
         * OpenAI 格式这里是普通字符串，不是数组
         */
        private String content;

        /** 工具调用（如果有） */
        @JsonProperty("tool_calls")
        private List<ToolCall> toolCalls;
    }

    /**
     * 工具调用
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolCall {
        /** 工具调用 ID */
        private String id;

        /** 类型，固定 "function" */
        private String type;

        /** 函数详情 */
        private FunctionCall function;
    }

    /**
     * 函数调用详情
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FunctionCall {
        /** 函数名 */
        private String name;

        /** 函数参数，JSON 字符串 */
        private String arguments;
    }

    /**
     * OpenAI 格式的 Token 用量
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OpenAIUsage {
        @JsonProperty("prompt_tokens")
        private int promptTokens;

        @JsonProperty("completion_tokens")
        private int completionTokens;

        @JsonProperty("total_tokens")
        private int totalTokens;
    }

    // ==================== 便捷方法 ====================

    /** 获取第一个选项（通常只有一个） */
    public Choice getFirstChoice() {
        return choices != null && !choices.isEmpty() ? choices.get(0) : null;
    }

    /** 是否成功 */
    public boolean isSuccess() {
        Choice first = getFirstChoice();
        return first != null && "stop".equals(first.getFinishReason());
    }
}