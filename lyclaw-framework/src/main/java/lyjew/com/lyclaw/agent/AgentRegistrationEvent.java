package lyjew.com.lyclaw.agent;

public class AgentRegistrationEvent {

    private final String agentId;
    private final AgentHandle handle;
    private final Type type;
    private final AgentState oldState;
    private final AgentState newState;

    public AgentRegistrationEvent(String agentId, AgentHandle handle, Type type,
                                  AgentState oldState, AgentState newState) {
        this.agentId = agentId;
        this.handle = handle;
        this.type = type;
        this.oldState = oldState;
        this.newState = newState;
    }

    public enum Type { REGISTERED, UNREGISTERED, STATE_CHANGED, HEALTH_CHANGED }

    public String getAgentId() { return agentId; }
    public AgentHandle getHandle() { return handle; }
    public Type getType() { return type; }
    public AgentState getOldState() { return oldState; }
    public AgentState getNewState() { return newState; }
}
