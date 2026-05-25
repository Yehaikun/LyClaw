package lyjew.com.lyclaw.reflect.impl.router;

import lyjew.com.lyclaw.annotation.reflect.Primitive;
import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.model.RouteDecision;
import lyjew.com.lyclaw.reflect.primitive.Router;
import lyjew.com.lyclaw.reflect.topology.PrimitiveType;

/**
 * 基于分数阈值的路由决策器。
 *
 * <p>决策逻辑：
 * <ul>
 *   <li>{@code score >= threshold && isSuccess → STOP} — 输出质量达标，终止迭代</li>
 *   <li>{@code iteration >= maxIterations → FALLBACK} — 已达上限，强制终止</li>
 *   <li>{@code 其他情况 → RETRY} — 启动 Reflector→Actor 反思-重试循环</li>
 * </ul>
 *
 * <p>默认阈值 0.7，可通过构造器自定义。阈值越低，对输出质量容忍度越高。
 */
@Primitive(type = PrimitiveType.ROUTER, name = "threshold", isDefault = true)
public class ThresholdRouter implements Router {

    private final double threshold;

    public ThresholdRouter() {
        this(0.7);
    }

    public ThresholdRouter(double threshold) {
        this.threshold = threshold;
    }

    @Override
    public RouteDecision route(ReflectionContext ctx, Evaluation evaluation, int iteration, int maxIterations) {
        // 空评估视为失败
        if (evaluation == null) {
            return iteration >= maxIterations ? RouteDecision.FALLBACK : RouteDecision.RETRY;
        }

        // 达标即停
        if (evaluation.isSuccess() && evaluation.getScore() >= threshold) {
            return RouteDecision.STOP;
        }

        // 超限兜底
        if (iteration >= maxIterations) {
            return RouteDecision.FALLBACK;
        }

        return RouteDecision.RETRY;
    }
}
