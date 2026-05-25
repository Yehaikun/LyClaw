package lyjew.com.lyclaw.reflect.condition;

import java.util.Set;

import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.topology.Edge;
import lyjew.com.lyclaw.reflect.topology.EdgeCondition;

/**
 * 评估始终通过的条件：ALWAYS / ON_BRANCH。
 */
public class AlwaysPassEvaluator implements ConditionEvaluator {

    private static final Set<EdgeCondition> SUPPORTED = Set.of(
            EdgeCondition.ALWAYS,
            EdgeCondition.ON_BRANCH
    );

    @Override
    public Set<EdgeCondition> supportedConditions() {
        return SUPPORTED;
    }

    @Override
    public boolean matches(Edge edge, ReflectionContext ctx) {
        return true;
    }
}
