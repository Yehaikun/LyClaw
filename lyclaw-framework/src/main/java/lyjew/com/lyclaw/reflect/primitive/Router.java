package lyjew.com.lyclaw.reflect.primitive;

import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.model.RouteDecision;

import java.util.function.Consumer;

@FunctionalInterface
public interface Router extends ReflectionPrimitive {
    RouteDecision route(ReflectionContext ctx, Evaluation evaluation, int iteration, int maxIterations);

    /**
     * 流式路由决策 — 在LLM决策过程中通过chunkSink推送中间文本块，完成后返回RouteDecision。
     * 默认回退到同步 {@link #route}。
     */
    default RouteDecision routeStream(ReflectionContext ctx, Evaluation evaluation,
                                       int iteration, int maxIterations, Consumer<String> chunkSink) {
        return route(ctx, evaluation, iteration, maxIterations);
    }
}
