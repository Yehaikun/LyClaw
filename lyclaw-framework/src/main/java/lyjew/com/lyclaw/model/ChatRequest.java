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
 * 聊天请求模型（Chat Request），封装了一次完整的 AI 大模型对话请求的所有参数和配置信息。
 *
 * <p>本类是 LyClaw 框架请求处理链中的核心数据模型，承载了从应用层发起到 AI 模型层的
 * 所有请求参数。作为框架内部的统一请求格式，它屏蔽了不同 AI Provider（OpenAI、DeepSeek、
 * Groq 等）在请求格式上的差异，使得上层应用和中间件（如拦截器、路由策略、装饰器链）
 * 可以基于统一的数据结构进行编程，而无需关心底层 Provider 的具体 API 协议。底层适配器
 * （如 {@code OpenAiProtocolChatModel}）负责将 ChatRequest 转换为其 Provider 对应的
 * 原生请求格式。
 *
 * <p>核心字段涵盖了一次 AI 对话请求所需的所有维度：
 * <ul>
 *   <li><b>会话与消息</b>：sessionId（会话唯一标识，用于关联持久化的会话记录）、
 *       messages（消息历史列表，包含用户和助手的完整对话上下文）、systemPrompt
 *       （系统提示词，用于设定 AI 的角色定位、行为准则和回答风格）</li>
 *   <li><b>模型与采样</b>：model（指定使用的模型名称，如 "gpt-4o"、"deepseek-v4-flash"，
 *       为空时使用 Provider 默认模型）、maxTokens（最大生成令牌数，控制回复长度）、
 *       temperature（采样温度，0~2 之间，值越低输出越确定和保守，值越高输出越随机和
 *       多样化）、topP（核采样参数，限制候选词的概率质量累计值，与 temperature 配合
 *       使用可精确控制输出的随机性）</li>
 *   <li><b>流式与工具</b>：stream（是否启用 SSE 流式响应，true 时响应通过 Server-Sent
 *       Events 增量推送）、tools（可用工具的定义列表，告知模型可调用的外部工具及其
 *       参数 Schema）、toolChoice（工具选择策略，如 "auto" 让模型自行决定、"none"
 *       禁用工具调用、或指定特定工具名强制调用）</li>
 *   <li><b>思考模式</b>：thinkingEnabled（是否启用扩展推理/思维链模式，DeepSeek 等
 *       模型支持）、thinkingBudget（思考模式的 Token 预算，限制推理过程的最大 Token
 *       消耗）</li>
 *   <li><b>其他控制</b>：stopSequences（停止序列列表，模型生成遇到这些文本时立即停止）、
 *       extras（扩展参数 Map，用于传递 Provider 特定的非标准参数，保证了框架的扩展性）</li>
 * </ul>
 *
 * <p>使用 Lombok 的 {@code @Data} 和 {@code @Builder} 注解自动生成所有字段的 getter/setter
 * 方法，以及建造者模式的构建器。同时提供了若干便捷判断方法：{@link #hasSystemPrompt()}
 * 判断是否设置了系统提示词、{@link #hasTools()} 判断是否配置了工具、{@link #getLastUserMessage()}
 * 获取消息列表中的最后一条用户消息内容、{@link #getMessageCount()} 获取消息总数。
 *
 * @see lyjew.com.lyclaw.model.Message
 * @see lyjew.com.lyclaw.model.ToolDefinition
 * @see lyjew.com.lyclaw.model.ModelResponse
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

    // ── Phase 2-4 预埋字段 ────────────────────────────────────────

    /** 目标 Agent ID，为空时使用默认 Agent。Phase 3 agent routing */
    @Builder.Default
    private String agentId = "";

    /** 思考级别: off, minimal, low, medium, high, xhigh, adaptive, max。Phase 2 thinking control */
    @Builder.Default
    private String thinkingLevel = "";

    /** 推理级别。Phase 2 reasoning control */
    @Builder.Default
    private String reasoningLevel = "";

    /** 详细度级别。Phase 2 verbose control */
    @Builder.Default
    private String verboseLevel = "";

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
