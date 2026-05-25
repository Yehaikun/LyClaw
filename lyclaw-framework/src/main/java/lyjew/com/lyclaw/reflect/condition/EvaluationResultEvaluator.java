package lyjew.com.lyclaw.reflect.condition;

import java.util.Set;

import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.topology.Edge;
import lyjew.com.lyclaw.reflect.topology.EdgeCondition;

/**
 * 评估基于 Evaluation 成功/失败的边条件：ON_SUCCESS / ON_FAIL。
 */
public class EvaluationResultEvaluator implements ConditionEvaluator {

    private static final Set<EdgeCondition> SUPPORTED = Set.of(
            EdgeCondition.ON_SUCCESS,
            EdgeCondition.ON_FAIL
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
            case ON_SUCCESS -> eval.isSuccess();
            case ON_FAIL -> !eval.isSuccess();
            default -> false;
        };
    }
}
