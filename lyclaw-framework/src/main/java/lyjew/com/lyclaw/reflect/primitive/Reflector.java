package lyjew.com.lyclaw.reflect.primitive;

import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;

import java.util.function.Consumer;

@FunctionalInterface
public interface Reflector extends ReflectionPrimitive {
    /** 根据评估结果生成反思文本，写入 ReflectionContext.currentReflection */
    String reflect(ReflectionContext ctx, Evaluation evaluation);

    /** 流式反思 — 在生成过程中通过 chunkSink 推送中间文本块，完成后返回反思全文 */
    default String reflectStream(ReflectionContext ctx, Evaluation evaluation,
                                  Consumer<String> chunkSink) {
        return reflect(ctx, evaluation);
    }
}
