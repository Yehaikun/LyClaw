package lyjew.com.lyclaw.memory.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.temporal.TemporalDecayFunction;
import org.springframework.stereotype.Component;

/**
 * 指数衰减函数实现，用于计算记忆的时间权重。
 *
 * <p>衰减公式：strength = exp(-baseDecayFactor * daysSinceCreation)</p>
 *
 * <p>特点：初期衰减快，随着时间推移强度趋近于 0。
 * 适用于需要快速遗忘的短期记忆场景（如感知层和短期记忆层）。</p>
 *
 * <p>参数说明：
 * <ul>
 *   <li>daysSinceCreation：自创建以来的天数，负值会被截断为 0</li>
 *   <li>baseDecayFactor：基础衰减系数，越大衰减越快，负值会被截断为 0</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class ExponentialDecayFunction implements TemporalDecayFunction {

    /**
     * 计算指数衰减后的强度值。
     *
     * @param daysSinceCreation 自创建以来的天数
     * @param baseDecayFactor   基础衰减系数
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
        return Math.exp(-baseDecayFactor * daysSinceCreation);
    }

    @Override
    public String getName() { return "exponential"; }
}
