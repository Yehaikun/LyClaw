package lyjew.com.lyclaw.event.impl;

import lyjew.com.lyclaw.agent.AgentState;
import lyjew.com.lyclaw.event.Event;

/**
 * Agent 状态变更事件 —— 当 Agent 的状态发生变化时发布。
 *
 * <p>AgentCoordinator 在 Agent 状态变更时发布此事件，
 * 供调度器、日志模块和 UI 监控模块消费。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Event
 */
public class AgentStateChangedEvent extends Event {

    /** Agent ID */
    private final String agentId;

    /** 旧状态 */
    private final AgentState oldState;

    /** 新状态 */
    private final AgentState newState;

    /**
     * 构造 Agent 状态变更事件。
     *
     * @param source   事件来源
     * @param agentId  Agent ID
     * @param oldState 旧状态
     * @param newState 新状态
     */
    public AgentStateChangedEvent(String source, String agentId,
                                  AgentState oldState, AgentState newState) {
        super(source, "AGENT_STATE_CHANGED");
        this.agentId = agentId;
        this.oldState = oldState;
        this.newState = newState;
    }

    /** @return Agent ID */
    public String getAgentId() { return agentId; }

    /** @return 旧状态 */
    public AgentState getOldState() { return oldState; }

    /** @return 新状态 */
    public AgentState getNewState() { return newState; }
}