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
 * 统一的模型对话请求体
 *
 * 屏蔽不同厂商请求格式的差异，上层业务只需要构建这一个对象，
 * 具体的适配器负责将其转换为厂商所需的请求格式。
 *
 * 设计模式：建造者模式（Builder Pattern）
 * 通过 Builder 模式让参数组合灵活且可读
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    // ========== 必填字段 ==========

    /**
     * 消息历史列表，按时间顺序排列
     * 至少包含一条用户消息
     */
    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    // ========== 常用可选字段 ==========

    /**
     * 系统提示词，设定模型的行为和角色
     * 不同厂商放置位置不同：
     * - Anthropic 格式：放在顶层 system 字段
     * - OpenAI 格式：放在 messages 数组中 role="system" 的第一条
     */
    @Builder.Default
    private String systemPrompt = "";

    /**
     * 模型名称，如 "MiniMax-M2.7"、"deepseek-v4-pro"
     * 为空时使用适配器的默认模型
     */
    @Builder.Default
    private String model = "";

    /**
     * 生成内容的最大 Token 数
     * null 或 0 表示使用厂商默认值
     */
    private Integer maxTokens;

    /**
     * 温度系数，控制输出的随机性
     * 范围因厂商而异：
     * - MiniMax / DeepSeek OpenAI: (0, 1]
     * - DeepSeek Anthropic: [0, 2]
     * null 表示使用厂商默认值
     */
    private Double temperature;

    /**
     * Top-P 采样策略
     * null 表示使用厂商默认值
     */
    private Double topP;

    // ========== 高级可选字段 ==========

    /**
     * 当前可用的工具列表
     * 包含内置工具和 MCP 工具，由 ToolRegistry 统一提供
     */
    @Builder.Default
    private List<ToolDefinition> tools = new ArrayList<>();

    /**
     * 是否启用模型的思考模式（仅支持思考模式的模型有效）
     */
    @Builder.Default
    private boolean thinkingEnabled = false;

    /**
     * 思考模式的 Token 预算（仅部分厂商支持）
     */
    private Integer thinkingBudget;

    /**
     * 强制工具调用——不为空时模型必须调用指定的工具
     * 为空时由模型自行判断是否调用工具
     */
    private String toolChoice;

    /**
     * 停止序列——模型遇到这些词时停止生成
     */
    @Builder.Default
    private List<String> stopSequences = new ArrayList<>();

    /**
     * 扩展字段——用于传递厂商特有的参数
     * 如 MiniMax 的 frequency_penalty、DeepSeek 的 reasoning_effort
     */
    @Builder.Default
    private Map<String, Object> extras = new HashMap<>();

    // ========== 便捷方法 ==========

    /** 是否有系统提示 */
    public boolean hasSystemPrompt() {
        return systemPrompt != null && !systemPrompt.isEmpty();
    }

    /** 是否有可用工具 */
    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }

    /** 获取最后一条用户消息的内容（常用于日志、会话名生成） */
    public String getLastUserMessage() {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if ("user".equals(msg.getRole())) {
                return msg.getContent();
            }
        }
        return "";
    }

    /** 消息总数（不含 system） */
    public int getMessageCount() {
        return messages != null ? messages.size() : 0;
    }
}