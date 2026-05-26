package lyjew.com.lyclaw.react;

import lyjew.com.lyclaw.model.ToolCall;

/**
 * 工具生命周期钩子，提供工具调用全过程的扩展点。
 */
public interface ToolLifecycleHook {

    /** 包装单次工具调用。 */
    default ToolCall wrapToolCall(ToolCall toolCall, AgentContext ctx) { return toolCall; }

    /** 包装 ToolExecutor，形成装饰链。 */
    default ToolExecutor wrapToolExecutor(ToolExecutor inner, AgentContext ctx) { return inner; }

    /** 工具调用前调用。 */
    default void beforeToolCall(String toolName, String toolCallId, String args, AgentContext ctx) {}

    /** 工具调用后调用。 */
    default void afterToolCall(String toolName, String toolCallId, String result, AgentContext ctx) {}

    /** 工具结果持久化时调用。 */
    default void toolResultPersist(String toolName, String result, AgentContext ctx) {}
}
