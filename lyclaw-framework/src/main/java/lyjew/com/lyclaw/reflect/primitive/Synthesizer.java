package lyjew.com.lyclaw.reflect.primitive;

import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;

import java.util.List;
import java.util.function.Consumer;

@FunctionalInterface
public interface Synthesizer extends ReflectionPrimitive {
    /** 合并多轮/多分支输出为最终结果 */
    String synthesize(ReflectionContext ctx, List<String> outputs, List<Evaluation> evaluations);

    /**
     * 流式合成 — 在LLM合成过程中通过chunkSink推送中间文本块，完成后返回最终结果。
     * 默认回退到同步 {@link #synthesize}。
     */
    default String synthesizeStream(ReflectionContext ctx, List<String> outputs,
                                     List<Evaluation> evaluations, Consumer<String> chunkSink) {
        return synthesize(ctx, outputs, evaluations);
    }
}
