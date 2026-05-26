package lyjew.com.lyclaw.react;

/**
 * SSE事件类型常量 — 单一定义点，替代散落各处的字符串字面量。
 *
 * <p>命名约定：使用下划线分隔的小写常量名，值保持与前端协议一致。
 * 所有发射和消费 SSE 事件的代码引用此处常量，确保前后端事件类型严格同步。
 */
public final class SseEventTypes {

    private SseEventTypes() {}

    /** 文本消息块（流式输出内容） */
    public static final String MESSAGE = "message";
    /** 深度思考/推理内容块 */
    public static final String THINKING = "thinking";
    /** 状态通知（如 "正在执行工具调用..."） */
    public static final String STATUS = "status";
    /** 工具调用执行中 */
    public static final String TOOL_CALL = "tool_call";
    /** 工具调用需要人工审批 */
    public static final String TOOL_APPROVAL = "tool_approval";
    /** 会话创建完成 */
    public static final String SESSION_CREATED = "session_created";
    /** 计划开始执行 */
    public static final String PLAN_START = "plan_start";
    /** 计划执行完成 */
    public static final String PLAN_COMPLETE = "plan_complete";
    /** 计划节点执行 */
    public static final String PLAN_NODE = "plan_node";
    /** 动作执行完成 */
    public static final String ACTION_COMPLETE = "action_complete";
    /** 上下文构建开始 */
    public static final String CONTEXT_BUILD_START = "context_build_start";
    /** 上下文构建完成 */
    public static final String CONTEXT_BUILD_COMPLETE = "context_build_complete";
    /** 安全拦截开始 */
    public static final String INTERCEPT_START = "intercept_start";
    /** 安全拦截阻止 */
    public static final String INTERCEPT_BLOCKED = "intercept_blocked";
    /** 安全拦截完成 */
    public static final String INTERCEPT_COMPLETE = "intercept_complete";
    /** 响应流终止信号 */
    public static final String DONE = "done";
    /** 响应完成 */
    public static final String RESPOND_COMPLETE = "respond_complete";
    /** 指标数据 */
    public static final String METRICS = "metrics";
    /** 反思摘要 */
    public static final String REFLECT_SUMMARY = "reflect_summary";
    /** 反思错误 */
    public static final String REFLECT_ERROR = "reflect_error";
    /** 反思步骤 */
    public static final String REFLECT_STEP = "reflect_step";
    /** 流式错误通知，前端收到后停止spinner并显示错误信息 */
    public static final String ERROR = "error";

    /** Phase 4: 子 Agent 实时进度事件（thinking/tool_call/message） */
    public static final String SUBAGENT_PROGRESS = "subagent_progress";

    // ========== Phase 1: Agent 注册发现事件 ==========

    /** Agent 注册完成 */
    public static final String AGENT_REGISTERED = "agent_registered";
    /** Agent 注销 */
    public static final String AGENT_UNREGISTERED = "agent_unregistered";
    /** Agent 状态变更 */
    public static final String AGENT_STATE_CHANGED = "agent_state_changed";
    /** Agent 健康状态变更 */
    public static final String AGENT_HEALTH_CHANGED = "agent_health_changed";

    // ========== Phase 2: Agent 路由事件 ==========

    /** 路由开始 */
    public static final String ROUTING_START = "routing_start";
    /** 路由决策 */
    public static final String ROUTING_DECISION = "routing_decision";
    /** 路由降级（无匹配） */
    public static final String ROUTING_FALLBACK = "routing_fallback";

    // ========== Phase 3: Agent 协作事件 ==========

    /** 协作开始 */
    public static final String COLLABORATION_START = "collaboration_start";
    /** 任务分解完成 */
    public static final String TASK_DECOMPOSED = "task_decomposed";
    /** 子任务开始 */
    public static final String SUB_TASK_START = "sub_task_start";
    /** 子任务完成 */
    public static final String SUB_TASK_COMPLETE = "sub_task_complete";
    /** 子任务失败 */
    public static final String SUB_TASK_FAIL = "sub_task_fail";
    /** 投票轮次 */
    public static final String VOTE_ROUND = "vote_round";
    /** 辩论轮次 */
    public static final String DEBATE_ROUND = "debate_round";
    /** 达成共识 */
    public static final String CONSENSUS_REACHED = "consensus_reached";
    /** 未达成共识 */
    public static final String CONSENSUS_FAILED = "consensus_failed";
    /** 聚合完成 */
    public static final String AGGREGATION_COMPLETE = "aggregation_complete";
}
