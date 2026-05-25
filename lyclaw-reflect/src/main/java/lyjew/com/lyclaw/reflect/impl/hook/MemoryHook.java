package lyjew.com.lyclaw.reflect.impl.hook;

import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.model.RouteDecision;
import lyjew.com.lyclaw.reflect.topology.ExecutionResult;
import lyjew.com.lyclaw.reflect.topology.TopologyEvent;

/**
 * 记忆钩子接口 — 在反射拓扑执行的 7 个关键生命周期节点触发回调。
 *
 * <p>钩子用途：
 * <ul>
 *   <li><b>持久化</b> — 将执行状态写入数据库或文件</li>
 *   <li><b>监控指标</b> — 记录耗时、成功率等指标</li>
 *   <li><b>记忆更新</b> — 自动提取成功经验和失败教训存储到记忆库</li>
 *   <li><b>审计日志</b> — 记录完整的决策链路</li>
 * </ul>
 *
 * <p>钩子按注册顺序同步调用，不应包含长时间阻塞操作。如有需要，请将耗时任务
 * 提交到独立线程池。钩子异常不会中断拓扑执行（异常被 hookRegistry 捕获并记录）。
 */
public interface MemoryHook {

    // ── 拓扑生命周期 ──

    /** 拓扑开始执行前触发 */
    default void onTopologyStart(ReflectionContext ctx, String topologyName) {}

    /** 拓扑执行完毕后触发（无论成功或失败） */
    default void onTopologyEnd(ReflectionContext ctx, ExecutionResult result) {}

    // ── 节点生命周期 ──

    /** Actor 执行前触发 */
    default void onActorBefore(ReflectionContext ctx, String actorName) {}

    /** Actor 执行后触发 */
    default void onActorAfter(ReflectionContext ctx, String actorName, String output) {}

    /** Evaluator 执行后触发 */
    default void onEvaluatorAfter(ReflectionContext ctx, Evaluation evaluation) {}

    /** Router 做出决策后触发 */
    default void onRouterAfter(ReflectionContext ctx, RouteDecision decision, int iteration) {}

    /** Reflector 执行后触发 */
    default void onReflectorAfter(ReflectionContext ctx, String reflection) {}

    // ── 事件钩子 ──

    /** 通用拓扑执行事件触发（替代以上细粒度回调的简化方式） */
    default void onTopologyEvent(TopologyEvent event) {}
}
