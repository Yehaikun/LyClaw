package lyjew.com.lyclaw.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天请求模型，封装了一次 AI 对话请求的所有参数。
 *
 * 包含会话标识、消息历史、系统提示词、模型选择、采样参数（temperature、topP）、
 * 工具定义、思考模式配置等完整信息。使用 Lombok 的 @Data/@Builder 注解自动生成
 * getter/setter 和建造者模式方法，同时提供若干便捷判断方法。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    /** 会话 ID，用于关联持久化的会话记录 */
    @Builder.Default
    private String sessionId = "";

    /** 消息历史列表，包含用户和助手的对话 */
    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    /** 系统提示词，用于设定 AI 的角色和行为 */
    @Builder.Default
    private String systemPrompt = "";

    /** 指定使用的模型名称 */
    @Builder.Default
    private String model = "";

    /** 最大生成令牌数 */
    private Integer maxTokens;

    /** 是否启用流式（SSE）响应 */
    @Builder.Default
    private boolean stream = false;

    /** 采样温度，控制输出的随机性，越接近 0 越确定 */
    private Double temperature;

    /** 核采样参数，限制采样词汇的概率质量 */
    private Double topP;

    /** 可用工具的定义列表，告知模型可调用的工具 */
    @Builder.Default
    private List<ToolDefinition> tools = new ArrayList<>();

    /** 是否启用思考模式（扩展推理） */
    @Builder.Default
    private boolean thinkingEnabled = false;

    /** 思考模式的预算（令牌数） */
    private Integer thinkingBudget;

    /** 工具选择策略，如 "auto"、"none" 或指定工具名 */
    private String toolChoice;

    /** 停止序列列表，模型遇到这些文本时停止生成 */
    @Builder.Default
    private List<String> stopSequences = new ArrayList<>();

    /** 扩展参数，用于传递模型特定的额外配置 */
    @Builder.Default
    private Map<String, Object> extras = new HashMap<>();

    /**
     * 判断请求中是否设置了系统提示词。
     *
     * @return true 如果系统提示词非空
     */
    public boolean hasSystemPrompt() {
        return systemPrompt != null && !systemPrompt.isEmpty();
    }

    /**
     * 判断请求中是否配置了工具。
     *
     * @return true 如果工具列表非空
     */
    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }

    /**
     * 获取消息列表中的最后一条用户消息内容。
     * 从消息列表末尾向前遍历，找到第一条 role 为 "user" 的消息并返回其内容。
     *
     * @return 最后一条用户消息的内容，没有时返回空字符串
     */
    public String getLastUserMessage() {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        // 从尾部倒序遍历消息列表
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            // 找到第一条用户角色消息即返回
            if ("user".equals(msg.getRole())) {
                return msg.getContent();
            }
        }
        return "";
    }

    /**
     * 获取消息列表的大小。
     *
     * @return 消息数量，messages 为 null 时返回 0
     */
    public int getMessageCount() {
        return messages != null ? messages.size() : 0;
    }
}
