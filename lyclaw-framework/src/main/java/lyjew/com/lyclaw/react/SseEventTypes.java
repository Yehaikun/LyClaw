package lyjew.com.lyclaw.react;

import lyjew.com.lyclaw.react.sse.SseEventType;

public final class SseEventTypes {
    private SseEventTypes() {}

    public static final String MESSAGE = SseEventType.MESSAGE.getEventName();
    public static final String THINKING = SseEventType.THINKING.getEventName();
    public static final String STATUS = SseEventType.STATUS.getEventName();
    public static final String TOOL_CALL = SseEventType.TOOL_CALL.getEventName();
    public static final String TOOL_APPROVAL = SseEventType.TOOL_APPROVAL.getEventName();
    public static final String SESSION_CREATED = SseEventType.SESSION_CREATED.getEventName();
    public static final String PLAN_START = SseEventType.PLAN_START.getEventName();
    public static final String PLAN_COMPLETE = SseEventType.PLAN_COMPLETE.getEventName();
    public static final String PLAN_NODE = "plan_node";
    public static final String ACTION_COMPLETE = SseEventType.ACTION_COMPLETE.getEventName();
    public static final String CONTEXT_BUILD_START = SseEventType.CONTEXT_BUILD_START.getEventName();
    public static final String CONTEXT_BUILD_COMPLETE = SseEventType.CONTEXT_BUILD_COMPLETE.getEventName();
    public static final String INTERCEPT_START = SseEventType.INTERCEPT_START.getEventName();
    public static final String INTERCEPT_BLOCKED = SseEventType.INTERCEPT_BLOCKED.getEventName();
    public static final String INTERCEPT_COMPLETE = SseEventType.INTERCEPT_COMPLETE.getEventName();
    public static final String DONE = SseEventType.DONE.getEventName();
    public static final String RESPOND_COMPLETE = SseEventType.RESPOND_COMPLETE.getEventName();
    public static final String METRICS = SseEventType.METRICS.getEventName();
    public static final String REFLECT_SUMMARY = "reflect_summary";
    public static final String REFLECT_ERROR = "reflect_error";
    public static final String REFLECT_STEP = "reflect_step";
    public static final String ERROR = SseEventType.ERROR.getEventName();
    public static final String AGENT_REGISTERED = SseEventType.AGENT_REGISTERED.getEventName();
    public static final String AGENT_UNREGISTERED = SseEventType.AGENT_UNREGISTERED.getEventName();
    public static final String AGENT_STATE_CHANGED = SseEventType.AGENT_STATE_CHANGED.getEventName();
    public static final String AGENT_HEALTH_CHANGED = SseEventType.AGENT_HEALTH_CHANGED.getEventName();
    public static final String ROUTING_START = SseEventType.ROUTING_START.getEventName();
    public static final String ROUTING_DECISION = SseEventType.ROUTING_DECISION.getEventName();
    public static final String ROUTING_FALLBACK = SseEventType.ROUTING_FALLBACK.getEventName();
    public static final String COLLABORATION_START = SseEventType.COLLABORATION_START.getEventName();
    public static final String TASK_DECOMPOSED = SseEventType.TASK_DECOMPOSED.getEventName();
    public static final String SUB_TASK_START = SseEventType.SUB_TASK_START.getEventName();
    public static final String SUB_TASK_COMPLETE = SseEventType.SUB_TASK_COMPLETE.getEventName();
    public static final String SUB_TASK_FAIL = SseEventType.SUB_TASK_FAIL.getEventName();
    public static final String VOTE_ROUND = SseEventType.VOTE_ROUND.getEventName();
    public static final String DEBATE_ROUND = SseEventType.DEBATE_ROUND.getEventName();
    public static final String CONSENSUS_REACHED = SseEventType.CONSENSUS_REACHED.getEventName();
    public static final String CONSENSUS_FAILED = SseEventType.CONSENSUS_FAILED.getEventName();
    public static final String AGGREGATION_COMPLETE = SseEventType.AGGREGATION_COMPLETE.getEventName();
    public static final String SUBAGENT_PROGRESS = SseEventType.SUBAGENT_PROGRESS.getEventName();
}
