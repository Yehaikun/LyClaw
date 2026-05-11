package lyjew.com.lyclaw.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 API 的请求体 DTO。
 *
 * <p>该类映射 OpenAI Chat Completions API 的请求 JSON 结构，用于向
 * DeepSeek 等兼容 OpenAI 格式的 LLM 服务发送请求。</p>
 *
 * <p>通过 {@link JsonProperty} 注解将 Java 驼峰命名映射到 API 的下划线命名
 * （如 {@code max_tokens}、{@code tool_choice}）。</p>
 *
 * <p>内部类包含：
 * <ul>
 *   <li>{@link Message} - 对话消息（含工具调用信息）</li>
 *   <li>{@link ToolCall} - 工具调用详情</li>
 *   <li>{@link FunctionCall} - 函数调用（名称+参数）</li>
 *   <li>{@link Thinking} - 思考模式配置</li>
 * </ul>
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAIRequest {

    /** 模型名称 */
    private String model;
    /** 消息列表 */
    private List<Message> messages;

    /** 是否启用流式输出，默认 false */
    @Builder.Default
    private boolean stream = false;

    /** 最大输出 Token 数 */
    @JsonProperty("max_tokens")
    private Integer maxTokens;
    /** 温度参数，控制输出随机性 */
    private Double temperature;
    /** Top-P 采样参数 */
    @JsonProperty("top_p")
    private Double topP;
    /** 停止序列列表 */
    @JsonProperty("stop")
    private List<String> stop;

    /** 工具定义列表（OpenAI tools 数组） */
    private List<Map<String, Object>> tools;

    /** 工具选择策略（"auto"/"none"/"required" 或具体工具名） */
    @JsonProperty("tool_choice")
    private Object toolChoice;
    /** 思考模式配置 */
    private Thinking thinking;
    /** 推理强度（"low"/"medium"/"high"），配合 thinking 使用 */
    @JsonProperty("reasoning_effort")
    private String reasoningEffort;

    /**
     * OpenAI 格式的对话消息。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Message {
        /** 角色：system/user/assistant/tool */
        private String role;
        /** 消息文本内容 */
        private String content;
        /** 工具调用 ID（tool 角色时必填） */
        @JsonProperty("tool_call_id")
        private String toolCallId;
        /** 工具调用列表（assistant 角色时可能包含） */
        @JsonProperty("tool_calls")
        private List<ToolCall> toolCalls;
    }

    /**
     * OpenAI 格式的工具调用（tool_calls 数组中的元素）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolCall {
        /** 工具调用唯一 ID */
        private String id;
        /** 类型，固定为 "function" */
        private String type;
        /** 函数调用详情 */
        private FunctionCall function;
    }

    /**
     * 函数调用详情（函数名和 JSON 参数）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FunctionCall {
        /** 函数/工具名称 */
        private String name;
        /** JSON 格式的参数 */
        private String arguments;
    }

    /**
     * DeepSeek 思考模式配置。
     * type 为 "enabled" 或 "disabled"。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Thinking {
        /** 思考模式状态："enabled" 或 "disabled" */
        private String type;
    }
}
