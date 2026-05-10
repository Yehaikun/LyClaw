package lyjew.com.lyclaw.infra.event;

import lyjew.com.lyclaw.agent.AgentState;
import lyjew.com.lyclaw.event.Event;

public class AgentStateChangedEvent extends Event {

    private final String agentId;
    private final AgentState fromState;
    private final AgentState toState;
    private final String sessionId;

    public AgentStateChangedEvent(String source, String agentId, AgentState fromState,
                                  AgentState toState, String sessionId) {
        super(source, "AGENT_STATE_CHANGED");
        this.agentId = agentId;
        this.fromState = fromState;
        this.toState = toState;
        this.sessionId = sessionId;
    }

    public String getAgentId() { return agentId; }
    public AgentState getFromState() { return fromState; }
    public AgentState getToState() { return toState; }
    public String getSessionId() { return sessionId; }
}
