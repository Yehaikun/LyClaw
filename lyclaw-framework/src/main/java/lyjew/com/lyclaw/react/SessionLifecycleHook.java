package lyjew.com.lyclaw.react;

/**
 * 会话生命周期钩子，提供会话开始/结束的扩展点。
 */
public interface SessionLifecycleHook {

    /** 会话开始时调用。 */
    default void sessionStart(String sessionId, AgentContext ctx) {}

    /** 会话结束时调用。 */
    default void sessionEnd(String sessionId, AgentContext ctx) {}
}
