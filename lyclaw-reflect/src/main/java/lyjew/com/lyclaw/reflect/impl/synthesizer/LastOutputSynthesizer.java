package lyjew.com.lyclaw.reflect.impl.synthesizer;

import lyjew.com.lyclaw.annotation.reflect.Primitive;
import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.primitive.Synthesizer;
import lyjew.com.lyclaw.reflect.topology.PrimitiveType;

import java.util.List;

/**
 * 最后轮输出合成器 — 取最后一轮输出作为最终结果（最简单策略）。
 *
 * <p>逻辑等同：{@code outputs.isEmpty() ? "" : outputs.get(outputs.size() - 1)}
 *
 * <p>适用场景：passthrough 等单轮拓扑、或 Reflexion 模式（最后一轮即反思后修复的版本）。
 */
@Primitive(type = PrimitiveType.SYNTHESIZER, name = "lastOutput", isDefault = true)
public class LastOutputSynthesizer implements Synthesizer {

    @Override
    public String synthesize(ReflectionContext ctx, List<String> outputs, List<Evaluation> evaluations) {
        return outputs.isEmpty() ? "" : outputs.get(outputs.size() - 1);
    }
}
