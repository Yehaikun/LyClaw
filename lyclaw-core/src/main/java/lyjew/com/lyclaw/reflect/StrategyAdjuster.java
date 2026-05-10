package lyjew.com.lyclaw.reflect;

/**
 * 策略调整器 —— 基于反思报告给出策略调整建议。
 *
 * <p>调整项: prompt改写 / 切换规划策略 / 增加工具调用 / 降低温度 / 触发人工干预</p>
 *
 * @since 2.0
 */
public interface StrategyAdjuster {

    StrategyAdjustment adjust(ReflectionReport report);
}
