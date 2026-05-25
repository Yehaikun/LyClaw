package lyjew.com.lyclaw.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * 对话消息模型，表示一次对话中的单条消息。
 *
 * 支持标准的 OpenAI 消息格式：role 标识消息角色（system/user/assistant/tool），
 * content 为消息正文，toolCalls 记录工具调用请求，toolCallId 关联工具调用结果。
 * 提供静态工厂方法快速创建各角色的消息实例。继承自 BaseDTO，
 * 使用 @JsonIgnoreProperties(ignoreUnknown = true) 来兼容模型返回的额外字段。
 */
@Data
@EqualsAndHashCode
@SuperBuilder
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Message {

    /** 消息角色：system、user、assistant 或 tool */
    private String role;
    /** 消息正文内容 */
    private String content;
    /** 生成此消息所使用的模型名称（assistant 消息） */
    private String model;
    /** 令牌使用统计（assistant 消息） */
    private Usage usage;
    /** 工具调用列表（assistant 消息中的 tool_calls） */
    private List<ToolCall> toolCalls;
    /** 工具调用 ID，用于将 tool 消息关联到对应的 tool_calls */
    private String toolCallId;
    /** 工具名称，用于追踪是哪个工具产生了这条消息。Phase 3 ContextPruner 使用 */
    private String toolName;

    /** 思考/推理内容，用于 DeepSeek 等 reasoning 模型（reasoning_content） */
    private String thinking;

    /**
     * 创建用户消息。
     *
     * @param content 消息内容
     * @return role 为 "user" 的消息实例
     */
    public static Message user(String content) {
        return Message.builder().role("user").content(content).build();
    }

    /**
     * 创建助手消息。
     *
     * @param content 消息内容
     * @return role 为 "assistant" 的消息实例
     */
    public static Message assistant(String content) {
        return Message.builder().role("assistant").content(content).build();
    }

    /**
     * 创建系统消息。
     *
     * @param content 消息内容
     * @return role 为 "system" 的消息实例
     */
    public static Message system(String content) {
        return Message.builder().role("system").content(content).build();
    }

    /**
     * 创建工具返回结果消息。
     *
     * @param toolCallId 关联的工具调用 ID
     * @param content    工具执行返回的内容
     * @return role 为 "tool" 的消息实例
     */
    public static Message tool(String toolCallId, String content) {
        return Message.builder().role("tool").toolCallId(toolCallId).content(content).build();
    }

    /**
     * 创建工具返回结果消息（含工具名称）。
     *
     * @param toolCallId 关联的工具调用 ID
     * @param toolName   工具名称
     * @param content    工具执行返回的内容
     * @return role 为 "tool" 的消息实例
     */
    public static Message tool(String toolCallId, String toolName, String content) {
        return Message.builder().role("tool").toolCallId(toolCallId).toolName(toolName).content(content).build();
    }
}
