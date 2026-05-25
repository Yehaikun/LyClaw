package lyjew.com.lyclaw.reflect.condition;

import static lyjew.com.lyclaw.react.ContextKeys.ROUTE_DECISION;

import java.util.Set;

import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.model.RouteDecision;
import lyjew.com.lyclaw.reflect.topology.Edge;
import lyjew.com.lyclaw.reflect.topology.EdgeCondition;

/**
 * 评估基于 RouteDecision 的边条件：ON_RETRY / ON_STOP / ON_FALLBACK / ON_CONTINUE。
 */
public class RouteDecisionEvaluator implements ConditionEvaluator {

    private static final Set<EdgeCondition> SUPPORTED = Set.of(
            EdgeCondition.ON_RETRY,
            EdgeCondition.ON_STOP,
            EdgeCondition.ON_FALLBACK,
            EdgeCondition.ON_CONTINUE
    );

    @Override
    public Set<EdgeCondition> supportedConditions() {
        return SUPPORTED;
    }

    @Override
    public boolean matches(Edge edge, ReflectionContext ctx) {
        RouteDecision decision = (RouteDecision) ctx.getAttribute(ROUTE_DECISION);
        if (decision == null) return false;
        return switch (decision) {
            case RETRY -> edge.getCondition() == EdgeCondition.ON_RETRY;
            case STOP -> edge.getCondition() == EdgeCondition.ON_STOP;
            case FALLBACK -> edge.getCondition() == EdgeCondition.ON_FALLBACK;
            case CONTINUE -> edge.getCondition() == EdgeCondition.ON_CONTINUE;
        };
    }
}
