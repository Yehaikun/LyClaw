package lyjew.com.lyclaw.memory.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.temporal.TemporalDecayFunction;
import org.springframework.stereotype.Component;

/**
 * 幂律衰减函数实现，用于计算记忆的时间权重。
 *
 * <p>衰减公式：strength = (1 + daysSinceCreation)^(-baseDecayFactor)</p>
 *
 * <p>特点：相比指数衰减，幂律衰减更平缓，具有"长尾"效应，
 * 即记忆在很长时间后仍保留一定强度。
 * 适用于需要长期保留记忆权重的场景（如长期记忆层）。</p>
 *
 * <p>参数说明：
 * <ul>
 *   <li>daysSinceCreation：自创建以来的天数，负值会被截断为 0</li>
 *   <li>baseDecayFactor：幂律指数，越大衰减越快，负值会被截断为 0</li>
 * </ul>
 * </p>
 *
 * <p>在认知科学中，遗忘曲线通常更符合幂律分布而非指数分布，
 * 因为人类对久远记忆的遗忘速度会逐渐放缓。</p>
 */
@Slf4j
@Component
public class PowerLawDecayFunction implements TemporalDecayFunction {

    /**
     * 计算幂律衰减后的强度值。
     *
     * @param daysSinceCreation 自创建以来的天数
     * @param baseDecayFactor   幂律指数
     * @return [0, 1] 范围内的衰减后强度值
     */
    @Override
    public double compute(long daysSinceCreation, double baseDecayFactor) {
        if (daysSinceCreation < 0) {
            log.warn("Negative daysSinceCreation ({}), clamping to 0", daysSinceCreation);
            daysSinceCreation = 0;
        }
        if (baseDecayFactor < 0.0) {
            log.warn("Negative baseDecayFactor ({}), clamping to 0", baseDecayFactor);
            baseDecayFactor = 0.0;
        }
        return Math.pow(1.0 + daysSinceCreation, -baseDecayFactor);
    }

    @Override
    public String getName() { return "power-law"; }
}
