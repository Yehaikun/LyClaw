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
 * Anthropic Messages API 的请求体 DTO。
 *
 * <p>映射发送到 Anthropic 端点（如 /v1/messages）的 JSON 请求体结构。
 * 与 OpenAI 格式的主要区别：</p>
 * <ul>
 *   <li>system 提示词是顶层字段而非消息数组中的特殊角色</li>
 *   <li>消息的 content 字段可以是对象或数组（包含 type 标记）</li>
 *   <li>max_tokens 为必填字段</li>
 *   <li>使用 stop_sequences 而非 stop</li>
 *   <li>thinking 配置使用 budget_tokens 而非 reasoning_effort</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicRequest {

    /** 模型名称 */
    private String model;
    /** 系统提示词，Anthropic 中为顶层字段 */
    private String system;
    /** 消息列表 */
    private List<Message> messages;

    /** 是否开启流式输出，默认 false */
    @Builder.Default
    private boolean stream = false;

    /** 最大生成 Token 数（必填） */
    @JsonProperty("max_tokens")
    private Integer maxTokens;
    /** 采样温度，范围 [0.01, 1.0] */
    private Double temperature;
    /** 核采样参数 */
    @JsonProperty("top_p")
    private Double topP;
    /** 停止序列 */
    @JsonProperty("stop_sequences")
    private List<String> stopSequences;

    /** 工具定义列表 */
    private List<Map<String, Object>> tools;

    /** 工具选择策略 */
    @JsonProperty("tool_choice")
    private Object toolChoice;
    /** 思考/推理模式配置 */
    private Thinking thinking;

    /**
     * 消息对象。
     *
     * <p>content 字段为 Object 类型，支持字符串或 content block 数组格式。</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Message {
        /** 消息角色：user 或 assistant */
        private String role;
        /** 消息内容，可以是字符串或 content block 数组 */
        private Object content;
    }

    /**
     * 思考模式配置。
     *
     * <p>type 为 "enabled" 时启用推理，
     * budget_tokens 控制推理预算（Token 数）。</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Thinking {
        /** 思考模式：enabled 或 disabled */
        private String type;
        /** 推理预算 Token 数 */
        @JsonProperty("budget_tokens")
        private Integer budgetTokens;
    }
}
