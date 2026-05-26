package lyjew.com.lyclaw.react;

import lyjew.com.lyclaw.model.Message;

import java.util.List;

/**
 * 模型生命周期钩子，提供 LLM 调用前后的扩展点。
 */
public interface ModelLifecycleHook {

    /** LLM 调用前回调，可注入上下文、调整消息列表。 */
    default List<Message> beforeModel(List<Message> messages, AgentContext ctx) { return messages; }

    /** LLM 响应后回调，可检测有害内容、记录日志。 */
    default String afterModel(String response, AgentContext ctx) { return response; }

    /** 模型解析前调用 —— 可拦截/修改模型选择。 */
    default void beforeModelResolve(AgentContext ctx) {}

    /** 模型调用开始时调用。 */
    default void modelCallStarted(AgentContext ctx) {}

    /** 模型调用结束时调用。 */
    default void modelCallEnded(AgentContext ctx) {}

    /** LLM 输入发送前调用 —— 审查发送给 LLM 的完整 prompt。 */
    default void llmInput(String prompt, AgentContext ctx) {}

    /** LLM 输出接收后调用 —— 审查 LLM 原始响应。 */
    default void llmOutput(String response, AgentContext ctx) {}
}
