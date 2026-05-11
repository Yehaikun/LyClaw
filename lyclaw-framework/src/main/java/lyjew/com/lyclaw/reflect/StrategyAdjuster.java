package lyjew.com.lyclaw.reflect;

/**
 * 策略调整器接口，根据反思报告的结论生成具体的策略调整方案。
 */
public interface StrategyAdjuster {

    /**
     * 根据反思报告生成策略调整建议。
     *
     * @param report 反思报告
     * @return 策略调整方案
     */
    StrategyAdjustment adjust(ReflectionReport report);
}
