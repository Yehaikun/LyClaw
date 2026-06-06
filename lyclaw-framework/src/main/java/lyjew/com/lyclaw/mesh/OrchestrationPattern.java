package lyjew.com.lyclaw.mesh;

/**
 * 编排模式 —— 定义多 Agent 协作的执行拓扑。
 *
 * <p>用户可以通过 {@link OrchestrationEngine} SPI 实现自定义编排模式。
 * 框架内置 6 种标准模式：</p>
 *
 * <ul>
 *   <li>{@link #SINGLE} — 路由到最匹配的 1 个 Agent</li>
 *   <li>{@link #CHAIN} — A → B → C 流水线处理</li>
 *   <li>{@link #FAN_OUT} — 并行派发 → 聚合结果</li>
 *   <li>{@link #DEBATE} — 多轮辩论 → 综合结论</li>
 *   <li>{@link #DAG} — 有向无环图依赖执行</li>
 *   <li>{@link #SUPERVISOR} — Supervisor 分解 → Workers 执行 → 汇总</li>
 * </ul>
 */
public enum OrchestrationPattern {
    SINGLE,
    CHAIN,
    FAN_OUT,
    DEBATE,
    DAG,
    SUPERVISOR
}
