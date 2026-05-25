package lyjew.com.lyclaw.reflect.impl.router;

import lyjew.com.lyclaw.annotation.reflect.Primitive;
import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.model.RouteDecision;
import lyjew.com.lyclaw.reflect.primitive.Router;
import lyjew.com.lyclaw.reflect.topology.PrimitiveType;

/**
 * 固定迭代次数路由决策器 — 无论评分如何，坚持完成指定迭代轮次。
 *
 * <p>决策逻辑：
 * <ul>
 *   <li>{@code iteration < fixedIterations → RETRY} — 未达指定轮次，继续反思-重试</li>
 *   <li>{@code iteration >= fixedIterations → STOP} — 达到指定轮次，强制结束</li>
 * </ul>
 *
 * <p>适用场景：需要确保足够的反思深度，不因早期评分达标就提前终止。
 * 例如：Self-Refine 模式通常需要 3 轮以上才允许停止。
 *
 * <p>如果评估为 null，达到 fixedIterations 时返回 FALLBACK 而非 STOP（表示评估失败）。
 */
@Primitive(type = PrimitiveType.ROUTER, name = "fixedIter")
public class FixedIterRouter implements Router {

    private final int fixedIterations;

    public FixedIterRouter() {
        this(3);
    }

    public FixedIterRouter(int fixedIterations) {
        if (fixedIterations < 1) throw new IllegalArgumentException("最小迭代次数不能小于 1");
        this.fixedIterations = fixedIterations;
    }

    @Override
    public RouteDecision route(ReflectionContext ctx, Evaluation evaluation, int iteration, int maxIterations) {
        // 超出全局最大限制则兜底
        if (iteration > maxIterations) {
            return RouteDecision.FALLBACK;
        }

        if (iteration < fixedIterations) {
            return RouteDecision.RETRY;
        }

        // 达到固定轮次后检查评估状态
        if (evaluation == null || !evaluation.isSuccess()) {
            return iteration >= maxIterations ? RouteDecision.FALLBACK : RouteDecision.STOP;
        }

        return RouteDecision.STOP;
    }
}
