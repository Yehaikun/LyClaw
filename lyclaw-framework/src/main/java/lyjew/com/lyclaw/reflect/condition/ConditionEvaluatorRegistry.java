package lyjew.com.lyclaw.reflect.condition;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import lyjew.com.lyclaw.reflect.topology.EdgeCondition;

/**
 * 边条件评估器注册表 — 自动收集所有 {@link ConditionEvaluator} Bean，
 * 按每个 {@link EdgeCondition} 索引对应的评估器，提供 O(1) 查找。
 */
public class ConditionEvaluatorRegistry {

    private final Map<EdgeCondition, ConditionEvaluator> evaluators = new EnumMap<>(EdgeCondition.class);

    public ConditionEvaluatorRegistry(List<ConditionEvaluator> evaluatorList) {
        for (ConditionEvaluator e : evaluatorList) {
            for (EdgeCondition cond : e.supportedConditions()) {
                evaluators.put(cond, e);
            }
        }
    }

    /**
     * 查找处理指定条件的评估器。
     * @return 对应的评估器，未找到时返回 null
     */
    public ConditionEvaluator get(EdgeCondition condition) {
        return evaluators.get(condition);
    }

    /** 已注册的条件类型数量 */
    public int size() {
        return evaluators.size();
    }
}
