package lyjew.com.lyclaw.react.sse;

import java.util.HashMap;
import java.util.Map;

public enum SseEventType {

    MESSAGE("message"),
    THINKING("thinking"),
    STATUS("status"),
    TOOL_CALL("tool_call"),
    TOOL_APPROVAL("tool_approval"),
    SESSION_CREATED("session_created"),
    SUBAGENT_PROGRESS("subagent_progress"),
    ERROR("error"),

    CONTEXT_BUILD_START("context_build_start"),
    CONTEXT_BUILD_COMPLETE("context_build_complete"),
    INTERCEPT_START("intercept_start"),
    INTERCEPT_BLOCKED("intercept_blocked"),
    INTERCEPT_COMPLETE("intercept_complete"),
    RESPOND_START("respond_start"),
    RESPOND_COMPLETE("respond_complete"),
    METRICS("metrics"),
    DONE("done"),

    PLAN_START("plan_start"),
    PLAN_COMPLETE("plan_complete"),
    ACTION_COMPLETE("action_complete"),

    AGENT_REGISTERED("agent_registered"),
    AGENT_UNREGISTERED("agent_unregistered"),
    AGENT_STATE_CHANGED("agent_state_changed"),
    AGENT_HEALTH_CHANGED("agent_health_changed"),
    ROUTING_START("routing_start"),
    ROUTING_DECISION("routing_decision"),
    ROUTING_FALLBACK("routing_fallback"),
    COLLABORATION_START("collaboration_start"),
    TASK_DECOMPOSED("task_decomposed"),
    SUB_TASK_START("sub_task_start"),
    SUB_TASK_COMPLETE("sub_task_complete"),
    SUB_TASK_FAIL("sub_task_fail"),
    AGGREGATION_COMPLETE("aggregation_complete"),
    VOTE_ROUND("vote_round"),
    DEBATE_ROUND("debate_round"),
    CONSENSUS_REACHED("consensus_reached"),
    CONSENSUS_FAILED("consensus_failed");

    private static final Map<String, SseEventType> BY_NAME = new HashMap<>();

    static {
        for (SseEventType t : values()) BY_NAME.put(t.eventName, t);
    }

    private final String eventName;

    SseEventType(String eventName) { this.eventName = eventName; }

    public String getEventName() { return eventName; }

    public static SseEventType fromString(String name) {
        return BY_NAME.get(name);
    }
}
