package lyjew.com.lyclaw.reflect.condition;

import static lyjew.com.lyclaw.react.ContextKeys.RETRIEVAL_DECISION;

import java.util.Set;

import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.model.RetrievalDecision;
import lyjew.com.lyclaw.reflect.topology.Edge;
import lyjew.com.lyclaw.reflect.topology.EdgeCondition;

/**
 * 评估基于检索决策的边条件：ON_RETRIEVE / ON_NO_RETRIEVE。
 */
public class RetrievalDecisionEvaluator implements ConditionEvaluator {

    private static final Set<EdgeCondition> SUPPORTED = Set.of(
            EdgeCondition.ON_RETRIEVE,
            EdgeCondition.ON_NO_RETRIEVE
    );

    @Override
    public Set<EdgeCondition> supportedConditions() {
        return SUPPORTED;
    }

    @Override
    public boolean matches(Edge edge, ReflectionContext ctx) {
        RetrievalDecision decision = (RetrievalDecision) ctx.getAttribute(RETRIEVAL_DECISION);
        if (decision == null) return false;
        return switch (decision) {
            case RETRIEVE -> edge.getCondition() == EdgeCondition.ON_RETRIEVE;
            case NO_RETRIEVE -> edge.getCondition() == EdgeCondition.ON_NO_RETRIEVE;
            default -> false;
        };
    }
}
