package lyjew.com.lyclaw.react;

/**
 * 子 Agent 生命周期钩子，提供子 Agent 生成/结束的扩展点。
 */
public interface SubagentLifecycleHook {

    /** 子 Agent 生成前调用。 */
    default void subagentSpawning(String childAgentId, String task, AgentContext ctx) {}

    /** 子 Agent 生成完成后调用。 */
    default void subagentSpawned(String childAgentId, String sessionKey, AgentContext ctx) {}

    /** 子 Agent 结束时调用。outcome: "ok", "error", "timeout", "killed"。 */
    default void subagentEnded(String childAgentId, String outcome, AgentContext ctx) {}
}
