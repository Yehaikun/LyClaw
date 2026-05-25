package lyjew.com.lyclaw.reflect.condition;

import java.util.Set;

import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.topology.Edge;
import lyjew.com.lyclaw.reflect.topology.EdgeCondition;

/**
 * 评估基于一致性的边条件：ON_CONSISTENT / ON_INCONSISTENT。
 */
public class ConsistencyEvaluator implements ConditionEvaluator {

    private static final Set<EdgeCondition> SUPPORTED = Set.of(
            EdgeCondition.ON_CONSISTENT,
            EdgeCondition.ON_INCONSISTENT
    );

    @Override
    public Set<EdgeCondition> supportedConditions() {
        return SUPPORTED;
    }

    @Override
    public boolean matches(Edge edge, ReflectionContext ctx) {
        Evaluation eval = ctx.getLastEvaluation();
        if (eval == null) return false;
        return switch (edge.getCondition()) {
            case ON_CONSISTENT -> eval.isConsistent();
            case ON_INCONSISTENT -> !eval.isConsistent();
            default -> false;
        };
    }
}
