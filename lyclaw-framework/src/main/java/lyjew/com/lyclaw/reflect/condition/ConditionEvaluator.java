package lyjew.com.lyclaw.reflect.condition;

import java.util.Set;

import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.topology.Edge;
import lyjew.com.lyclaw.reflect.topology.EdgeCondition;

/**
 * 边条件评估器 — 策略接口，评估 DAG 拓扑中某条边的条件是否满足。
 *
 * <p>每个实现处理一组 {@link EdgeCondition}，由
 * {@link ConditionEvaluatorRegistry} 自动发现并按条件类型索引。</p>
 *
 * <p>添加新条件类型：实现此接口并注册为 Spring Bean 即可，
 * 无需修改 {@code TopologyExecutor}。</p>
 */
public interface ConditionEvaluator {

    /** 该评估器处理的边条件类型集合 */
    Set<EdgeCondition> supportedConditions();

    /**
     * 评估该条件在当前上下文中是否满足。
     *
     * @param edge 待评估的边（含 conditionValue 阈值等）
     * @param ctx  当前反射上下文（含最新评估结果、路由决策等）
     * @return true 表示该边应该被遍历
     */
    boolean matches(Edge edge, ReflectionContext ctx);
}
