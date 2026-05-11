package lyjew.com.lyclaw.memory.temporal;

/**
 * 时间衰减函数接口，定义记忆强度随时间衰减的计算模型。
 *
 * 不同的衰减函数（指数衰减、线性衰减、对数衰减等）适用于不同类型的记忆。
 * 例如，事件类记忆可能衰减较快，而事实类记忆衰减较慢。
 * 实现类可被 {@link lyjew.com.lyclaw.memory.TemporalProps#computeDecay()} 间接调用。
 */
public interface TemporalDecayFunction {

    /**
     * 根据距创建天数和基础衰减因子计算当前衰减值。
     *
     * @param daysSinceCreation 距记忆创建的天数
     * @param baseDecayFactor   基础衰减因子（由记忆类型决定）
     * @return 当前衰减值 [0, 1]，1 表示无衰减，0 表示完全衰减
     */
    double compute(long daysSinceCreation, double baseDecayFactor);

    /**
     * 获取该衰减函数的名称标识。
     *
     * @return 函数名称（如 "exponential"、"linear"、"logarithmic"）
     */
    String getName();
}
