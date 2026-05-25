package lyjew.com.lyclaw.reflect.impl.synthesizer;

import lyjew.com.lyclaw.annotation.reflect.Primitive;
import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.primitive.Synthesizer;
import lyjew.com.lyclaw.reflect.topology.PrimitiveType;

import java.util.List;

/**
 * 最佳分数合成器 — 选择评估分数最高的一轮输出作为最终结果。
 *
 * <p>选择逻辑：
 * <ol>
 *   <li>遍历 evaluations 找到最高分对应的索引</li>
 *   <li>返回该索引对应的 outputs 内容</li>
 *   <li>如果 evaluations 为空或不存在对应输出，降级为最后一轮输出</li>
 * </ol>
 *
 * <p>适用场景：多分支 Fork/Join 拓扑中，选择各分支中表现最好的结果。
 */
@Primitive(type = PrimitiveType.SYNTHESIZER, name = "bestScore")
public class BestScoreSynthesizer implements Synthesizer {

    @Override
    public String synthesize(ReflectionContext ctx, List<String> outputs, List<Evaluation> evaluations) {
        if (outputs.isEmpty()) return "";

        // 无评估数据时返回最后一轮
        if (evaluations == null || evaluations.isEmpty()) {
            return outputs.get(outputs.size() - 1);
        }

        // 找到最高分索引
        int bestIdx = 0;
        double bestScore = -1.0;
        for (int i = 0; i < evaluations.size(); i++) {
            Evaluation e = evaluations.get(i);
            if (e != null && e.getScore() > bestScore) {
                bestScore = e.getScore();
                bestIdx = i;
            }
        }

        // 取对应的输出，索引越界时用最后一个
        if (bestIdx < outputs.size()) {
            return outputs.get(bestIdx);
        }
        return outputs.get(outputs.size() - 1);
    }
}
