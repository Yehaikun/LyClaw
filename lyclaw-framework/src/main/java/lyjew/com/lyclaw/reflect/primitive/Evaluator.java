package lyjew.com.lyclaw.reflect.primitive;

import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;

import java.util.function.Consumer;

@FunctionalInterface
public interface Evaluator extends ReflectionPrimitive {
    Evaluation evaluate(ReflectionContext ctx);

    /**
     * 流式评估 — 在LLM评分过程中通过chunkSink推送中间文本块，完成后返回Evaluation。
     * 默认回退到同步 {@link #evaluate}。
     */
    default Evaluation evaluateStream(ReflectionContext ctx, Consumer<String> chunkSink) {
        return evaluate(ctx);
    }
}
