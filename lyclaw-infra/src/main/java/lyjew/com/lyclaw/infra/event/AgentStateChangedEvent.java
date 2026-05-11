package lyjew.com.lyclaw.infra.event;

import lyjew.com.lyclaw.agent.AgentState;
import lyjew.com.lyclaw.event.Event;

/**
 * Agent 状态变更事件，在 Agent 的生命周期状态发生变化时发布。
 *
 * <p>携带 Agent ID、状态变更前后的状态值以及会话 ID，
 * 用于生命周期监控、任务调度和状态审计。</p>
 */
public class AgentStateChangedEvent extends Event {

    /** Agent 唯一标识 */
    private final String agentId;
    /** 变更前的状态 */
    private final AgentState fromState;
    /** 变更后的状态 */
    private final AgentState toState;
    /** 关联的会话 ID */
    private final String sessionId;

    /**
     * 构造一个 Agent 状态变更事件。
     *
     * @param source    事件来源标识
     * @param agentId   Agent ID
     * @param fromState 变更前状态
     * @param toState   变更后状态
     * @param sessionId 会话 ID
     */
    public AgentStateChangedEvent(String source, String agentId, AgentState fromState,
                                  AgentState toState, String sessionId) {
        super(source, "AGENT_STATE_CHANGED");
        this.agentId = agentId;
        this.fromState = fromState;
        this.toState = toState;
        this.sessionId = sessionId;
    }

    /** @return Agent ID */
    public String getAgentId() { return agentId; }
    /** @return 变更前状态 */
    public AgentState getFromState() { return fromState; }
    /** @return 变更后状态 */
    public AgentState getToState() { return toState; }
    /** @return 会话 ID */
    public String getSessionId() { return sessionId; }
}
