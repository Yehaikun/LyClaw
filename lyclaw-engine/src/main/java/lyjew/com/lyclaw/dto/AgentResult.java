package lyjew.com.lyclaw.dto;

/**
 * Agent 执行结果 —— AgentCoordinator.awaitResult() 的返回值 DTO。
 *
 * <p>当 AgentCoordinator 派发一个任务给子 Agent 后，调用方通过 awaitResult()
 * 获取子 Agent 的执行结果。这个对象封装了执行状态、结果摘要和详细信息。</p>
 *
 * <p><b>设计动机</b>：子 Agent 的执行结果需要统一的数据结构，
 * 包含执行状态（成功/失败/超时）、摘要信息和详细内容，便于主 Agent 决策。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>AgentCoordinator.dispatch() 的 CompletableFuture 返回值类型</li>
 *   <li>AgentChannel 中传递的子 Agent 执行结果</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class AgentResult {

    /** Agent ID —— 执行此任务的子 Agent 的唯一标识 */
    private final String agentId;

    /**
     * 执行状态。
     * <ul>
     *   <li>"COMPLETED" — 执行成功</li>
     *   <li>"FAILED" — 执行失败</li>
     *   <li>"TIMEOUT" — 执行超时</li>
     *   <li>"CANCELLED" — 已被取消</li>
     * </ul>
     */
    private final String status;

    /** 结果摘要 —— 简短的执行过程描述，用于主 Agent 快速了解结果 */
    private final String summary;

    /** 结果详情 —— 完整的执行结果内容，包含详细输出 */
    private final String detail;

    /** 执行耗时（毫秒） */
    private final long elapsedMs;

    /**
     * 构造一个 AgentResult 实例。
     *
     * @param agentId   Agent ID
     * @param status    执行状态
     * @param summary   结果摘要
     * @param detail    结果详情
     * @param elapsedMs 执行耗时（毫秒）
     */
    public AgentResult(String agentId, String status, String summary,
                       String detail, long elapsedMs) {
        this.agentId = agentId;
        this.status = status;
        this.summary = summary;
        this.detail = detail;
        this.elapsedMs = elapsedMs;
    }

    /** @return Agent ID */
    public String getAgentId() { return agentId; }

    /** @return 执行状态 */
    public String getStatus() { return status; }

    /** @return 结果摘要 */
    public String getSummary() { return summary; }

    /** @return 结果详情 */
    public String getDetail() { return detail; }

    /** @return 执行耗时（毫秒） */
    public long getElapsedMs() { return elapsedMs; }
}