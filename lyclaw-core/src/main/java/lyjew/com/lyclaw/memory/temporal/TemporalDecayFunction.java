package lyjew.com.lyclaw.memory.temporal;

/**
 * 时间衰减函数 —— 模拟艾宾浩斯遗忘曲线。
 *
 * <p>支持:
 * <ul>
 *   <li>指数衰减: e^{-λt}</li>
 *   <li>幂律衰减: (1 + t)^{-α}</li>
 *   <li>线性衰减: max(0, 1 - t/H)</li>
 * </ul></p>
 *
 * @since 2.0
 */
public interface TemporalDecayFunction {

    /** 计算时间衰减系数 [0.0, 1.0], 1.0表示无衰减 */
    double compute(long daysSinceCreation, double baseDecayFactor);

    String getName();
}
