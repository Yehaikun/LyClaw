package lyjew.com.lyclaw.react;

import java.util.List;

import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ToolCall;

/**
 * Agent 调用钩子 SPI，提供请求级和步级扩展点。
 *
 * <h3>执行顺序</h3>
 * <ol>
 *   <li>{@link #beforeRequest(AgentContext)} — 请求前（按 order 升序）</li>
 *   <li>Stage 管线执行</li>
 *   <li>ReAct 循环内：
 *     <ul>
 *       <li>{@link #beforeModel(List, AgentContext)} — 每次 LLM 调用前</li>
 *       <li>{@link #afterModel(String, AgentContext)} — 每次 LLM 响应后</li>
 *       <li>{@link #wrapToolCall(ToolCall, AgentContext)} — 每次工具调用包装</li>
 *     </ul>
 *   </li>
 *   <li>{@link #wrapToolExecutor(ToolExecutor, AgentContext)} — 工具执行器装饰链</li>
 *   <li>{@link #afterResult(String, AgentContext)} — 结果返回前（按 order 降序）</li>
 * </ol>
 */
public interface AgentHook {

    /** 请求发送前回调。抛出异常可中断请求。 */
    default void beforeRequest(AgentContext ctx) {}

    /**
     * LLM 调用前回调，可注入计划上下文、动态调整消息列表。
     * @param messages 当前消息列表（不可变视图）
     * @param ctx Agent 上下文
     * @return 修改后的消息列表，返回原列表表示不修改
     */
    default List<Message> beforeModel(List<Message> messages, AgentContext ctx) {
        return messages;
    }

    /**
     * LLM 响应后回调，可检测有害内容、记录日志。
     * @param response LLM 原始响应文本
     * @param ctx Agent 上下文
     * @return 处理后的响应文本
     */
    default String afterModel(String response, AgentContext ctx) {
        return response;
    }

    /** 包装单次工具调用，比 wrapToolExecutor 更细粒度。 */
    default ToolCall wrapToolCall(ToolCall toolCall, AgentContext ctx) {
        return toolCall;
    }

    /**
     * 包装 ToolExecutor，形成装饰链。
     * @param inner 当前链中的 ToolExecutor
     * @param ctx   Agent 上下文
     * @return 包装后的 ToolExecutor
     */
    default ToolExecutor wrapToolExecutor(ToolExecutor inner, AgentContext ctx) {
        return inner;
    }

    /**
     * 结果返回前回调。
     * @param result ReAct 引擎返回的最终文本
     * @param ctx    Agent 上下文
     * @return 处理后的结果
     */
    default String afterResult(String result, AgentContext ctx) {
        return result;
    }

    /** 优先级，数值越小越先执行。默认 100。 */
    default int getOrder() { return 100; }

    // ── 模型生命周期 ──────────────────────────────────────────

    /** 模型解析前调用 —— 可拦截/修改模型选择。 */
    default void beforeModelResolve(AgentContext ctx) {}

    /** 模型调用开始时调用 —— 记录/审计 LLM 调用开始。 */
    default void modelCallStarted(AgentContext ctx) {}

    /** 模型调用结束时调用 —— 记录/审计 LLM 调用结束。 */
    default void modelCallEnded(AgentContext ctx) {}

    /** LLM 输入发送前调用 —— 审查发送给 LLM 的完整 prompt。 */
    default void llmInput(String prompt, AgentContext ctx) {}

    /** LLM 输出接收后调用 —— 审查 LLM 原始响应。 */
    default void llmOutput(String response, AgentContext ctx) {}

    // ── Agent 生命周期 ────────────────────────────────────────

    /** Agent 启动时调用（DEPRECATED：使用 beforeAgentRun）。 */
    default void beforeAgentStart(AgentContext ctx) {}

    /** Agent 回复发送前调用 —— 可过滤/转换最终回复。 */
    default void beforeAgentReply(String reply, AgentContext ctx) {}

    /** Agent 最终化前调用 —— 修订门控：返回 CONTINUE/REVISE/FINALIZE。 */
    default AgentFinalizeResult beforeAgentFinalize(AgentContext ctx) {
        return AgentFinalizeResult.continue_();
    }

    /** Agent 运行结束时调用 —— 清理、通知。 */
    default void agentEnd(AgentContext ctx) {}

    /** Agent 运行开始前调用 —— 门控：可抛出异常阻止运行。 */
    default void beforeAgentRun(AgentContext ctx) {}

    // ── 工具生命周期 ────────────────────────────────────────

    /** 工具调用前调用 —— 可门控（PASS/BLOCK）。参数为 JSON 字符串。 */
    default void beforeToolCall(String toolName, String toolCallId, String args, AgentContext ctx) {}

    /** 工具调用后调用 —— 记录结果、副作用。 */
    default void afterToolCall(String toolName, String toolCallId, String result, AgentContext ctx) {}

    /** 工具结果持久化时调用。 */
    default void toolResultPersist(String toolName, String result, AgentContext ctx) {}

    // ── 会话生命周期 ────────────────────────────────────────

    /** 会话开始时调用。 */
    default void sessionStart(String sessionId, AgentContext ctx) {}

    /** 会话结束时调用。 */
    default void sessionEnd(String sessionId, AgentContext ctx) {}

    // ── 子Agent生命周期 ───────────────────────────────────

    /** 子 Agent 生成前调用。 */
    default void subagentSpawning(String childAgentId, String task, AgentContext ctx) {}

    /** 子 Agent 生成完成后调用。 */
    default void subagentSpawned(String childAgentId, String sessionKey, AgentContext ctx) {}

    /** 子 Agent 结束时调用。outcome: "ok", "error", "timeout", "killed"。 */
    default void subagentEnded(String childAgentId, String outcome, AgentContext ctx) {}

    // ── Compaction ───────────────────────────────────────────

    /** 上下文压缩前调用。 */
    default void beforeCompaction(AgentContext ctx) {}

    /** 上下文压缩后调用。 */
    default void afterCompaction(AgentContext ctx) {}

    // ── 消息生命周期 ────────────────────────────────────────

    /** 消息接收时调用 —— 可过滤/转换入站消息。 */
    default void messageReceived(Message msg, AgentContext ctx) {}

    /** 消息发送前调用。 */
    default void messageSending(String msg, AgentContext ctx) {}

    /** 消息发送后调用。 */
    default void messageSent(String msg, AgentContext ctx) {}

    // ── 心跳检测 ────────────────────────────────────────────

    /** 心跳提示词贡献 —— 返回要追加到心跳 prompt 的字符串。 */
    default String heartbeatPromptContribution(AgentContext ctx) { return ""; }
}
