package lyjew.com.lyclaw.react;

/**
 * Agent 生命周期钩子，提供 Agent 运行全过程的扩展点。
 */
public interface AgentLifecycleHook {

    /** 请求发送前回调。抛出异常可中断请求。 */
    default void beforeRequest(AgentContext ctx) {}

    /** 结果返回前回调。 */
    default String afterResult(String result, AgentContext ctx) { return result; }

    /** Agent 运行开始前调用。 */
    default void beforeAgentRun(AgentContext ctx) {}

    /** Agent 运行结束时调用。 */
    default void agentEnd(AgentContext ctx) {}

    /** Agent 回复发送前调用。 */
    default void beforeAgentReply(String reply, AgentContext ctx) {}

    /** Agent 最终化前调用 —— 修订门控。 */
    default AgentFinalizeResult beforeAgentFinalize(AgentContext ctx) {
        return AgentFinalizeResult.continue_();
    }
}
