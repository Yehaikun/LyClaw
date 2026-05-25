package lyjew.com.lyclaw.reflect.condition;

import java.util.Set;

import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.topology.Edge;
import lyjew.com.lyclaw.reflect.topology.EdgeCondition;

/**
 * 评估基于分数阈值的边条件：ON_SCORE_ABOVE / ON_SCORE_BELOW /
 * ON_IMPORTANCE_ABOVE / ON_IMPORTANCE_BELOW。
 */
public class ScoreThresholdEvaluator implements ConditionEvaluator {

    private static final Set<EdgeCondition> SUPPORTED = Set.of(
            EdgeCondition.ON_SCORE_ABOVE,
            EdgeCondition.ON_SCORE_BELOW,
            EdgeCondition.ON_IMPORTANCE_ABOVE,
            EdgeCondition.ON_IMPORTANCE_BELOW
    );

    @Override
    public Set<EdgeCondition> supportedConditions() {
        return SUPPORTED;
    }

    @Override
    public boolean matches(Edge edge, ReflectionContext ctx) {
        Evaluation eval = ctx.getLastEvaluation();
        if (eval == null || edge.getConditionValue() == null) return false;
        double threshold;
        try {
            threshold = Double.parseDouble(edge.getConditionValue());
        } catch (NumberFormatException e) {
            return false;
        }
        return switch (edge.getCondition()) {
            case ON_SCORE_ABOVE -> eval.getScore() >= threshold;
            case ON_SCORE_BELOW -> eval.getScore() < threshold;
            case ON_IMPORTANCE_ABOVE -> eval.getImportanceScore() >= threshold;
            case ON_IMPORTANCE_BELOW -> eval.getImportanceScore() < threshold;
            default -> false;
        };
    }
}
