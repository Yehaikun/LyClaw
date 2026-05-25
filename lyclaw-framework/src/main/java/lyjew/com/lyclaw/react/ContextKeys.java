package lyjew.com.lyclaw.react;

/**
 * 上下文属性键常量 — 替代散落在 AgentContext、ReflectionContext 等处的硬编码字符串。
 *
 * <p>所有 {@code getAttribute / setAttribute} 调用引用此处常量，确保键名唯一且可追溯。
 * 按作用域分组：AgentContext 顶层键、ReflectionContext 键、扩展 Map 键、快照键。
 */
public final class ContextKeys {

    private ContextKeys() {}

    // ──── AgentContext 顶层属性键 ────

    /** 最终响应文本（由 SSE message 事件拼接） */
    public static final String FINAL_RESPONSE = "finalResponse";
    /** 幻觉检测标记（OutputGuardHook 设置） */
    public static final String HALLUCINATION_DETECTED = "hallucination_detected";
    /** 审批待处理前缀（拼接 toolCallId 组成完整键） */
    public static final String APPROVAL_PENDING_PREFIX = "approval_pending_";
    /** AgentContext 自身引用（传递到 ToolProviderRequest 属性） */
    public static final String AGENT_CONTEXT = "agentContext";
    /** 记忆条目列表（ContextBuildStage 输出 → PlanExecutionStage 消费） */
    public static final String MEMORY_ENTRIES = "memoryEntries";
    /** 子代理配置对象（SubagentSpawner 存储） */
    public static final String SUBAGENT_CONFIG = "subagentConfig";
    /** 代理扩展 Map（ModelResolutionService / SubagentSpawner 消费） */
    public static final String AGENT_EXTENSIONS = "agentExtensions";
    /** 子会话对象（SubagentSpawner 创建后存储） */
    public static final String CHILD_SESSION = "childSession";
    /** 任务摘要字符串（PlanExecutionStage 产出 → ReflectionTopologyStage 消费） */
    public static final String TASK_SUMMARY = "taskSummary";
    /** 工具动态过滤开关 */
    public static final String TOOL_DYNAMIC_FILTERING = "tool.dynamicFiltering";
    /** 错误信息（ToolCallLoop 在工具调用失败时设置） */
    public static final String ERROR = "error";

    // ──── ReflectionContext 属性键 ────

    /** 路由决策枚举（Router 节点设置 → RouteDecisionEvaluator 消费） */
    public static final String ROUTE_DECISION = "routeDecision";
    /** 检索决策枚举（RETRIEVAL_GATE 节点设置 → RetrievalDecisionEvaluator 消费） */
    public static final String RETRIEVAL_DECISION = "retrievalDecision";
    /** 反思摘要 Map（ReflectionPersistenceHook 构建 → ReflectionTopologyStage 消费） */
    public static final String REFLECTION_SUMMARY = "reflectionSummary";

    // ──── 扩展 Map 嵌套键 (agentExtensions 内的 key) ────

    /** 扩展：模型名称 */
    public static final String EXT_MODEL = "model";
    /** 扩展：提供商 */
    public static final String EXT_PROVIDER = "provider";
    /** 扩展：回退链 */
    public static final String EXT_FALLBACK = "fallback";
    /** 扩展：图像模型 */
    public static final String EXT_IMAGE_MODEL = "imageModel";
    /** 扩展：允许的子代理列表 */
    public static final String EXT_ALLOW_AGENTS = "allowAgents";
    /** 扩展：委托模式 */
    public static final String EXT_DELEGATION_MODE = "delegationMode";
    /** 扩展：最大派生深度 */
    public static final String EXT_MAX_SPAWN_DEPTH = "maxSpawnDepth";
    /** 扩展：每代理最大子代理数 */
    public static final String EXT_MAX_CHILDREN_PER_AGENT = "maxChildrenPerAgent";
    /** 扩展：最大并发数 */
    public static final String EXT_MAX_CONCURRENT = "maxConcurrent";
    /** 扩展：运行超时秒数 */
    public static final String EXT_RUN_TIMEOUT_SECONDS = "runTimeoutSeconds";
    /** 扩展：是否要求代理 ID */
    public static final String EXT_REQUIRE_AGENT_ID = "requireAgentId";
    /** 扩展：思考级别 */
    public static final String EXT_THINKING = "thinking";
}
