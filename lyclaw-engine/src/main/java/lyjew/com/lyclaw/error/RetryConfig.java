package lyjew.com.lyclaw.error;

/**
 * 重试配置值对象 —— ErrorPolicy 通过它定义最大重试次数、退避策略和超时。
 *
 * <p>ErrorPolicy.getRetryConfig() 的返回值。ToolCallLoop 根据 RetryConfig
 * 判断是否应该重试、重试间隔和总超时时间。</p>
 *
 * <p><b>退避策略说明</b>：
 * <ul>
 *   <li>FIXED：固定间隔，每次重试等待 fixedDelayMs</li>
 *   <li>EXPONENTIAL：指数退避，第 n 次重试等待 baseDelayMs * 2^(n-1)</li>
 *   <li>LINEAR：线性递增，第 n 次重试等待 baseDelayMs * n</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ErrorPolicy
 */
public class RetryConfig {

    /** 退避策略 */
    public enum BackoffStrategy {
        FIXED,
        EXPONENTIAL,
        LINEAR
    }

    /** 最大重试次数 */
    private final int maxRetries;

    /** 基础延迟（ms），具体含义取决于退避策略 */
    private final long baseDelayMs;

    /** 固定延迟（ms），仅在 FIXED 策略下使用 */
    private final long fixedDelayMs;

    /** 退避策略 */
    private final BackoffStrategy strategy;

    /**
     * 构造重试配置。
     *
     * @param maxRetries    最大重试次数
     * @param baseDelayMs   基础延迟
     * @param fixedDelayMs  固定延迟（FIXED 策略使用）
     * @param strategy      退避策略
     */
    public RetryConfig(int maxRetries, long baseDelayMs,
                       long fixedDelayMs, BackoffStrategy strategy) {
        this.maxRetries = maxRetries;
        this.baseDelayMs = baseDelayMs;
        this.fixedDelayMs = fixedDelayMs;
        this.strategy = strategy;
    }

    /**
     * 使用默认 EXPONENTIAL 策略创建重试配置。
     *
     * @param maxRetries  最大重试次数
     * @param baseDelayMs 基础延迟（ms）
     * @return 重试配置
     */
    public static RetryConfig exponential(int maxRetries, long baseDelayMs) {
        return new RetryConfig(maxRetries, baseDelayMs, 0, BackoffStrategy.EXPONENTIAL);
    }

    /**
     * 使用 FIXED 策略创建重试配置。
     *
     * @param maxRetries   最大重试次数
     * @param fixedDelayMs 固定延迟（ms）
     * @return 重试配置
     */
    public static RetryConfig fixed(int maxRetries, long fixedDelayMs) {
        return new RetryConfig(maxRetries, 0, fixedDelayMs, BackoffStrategy.FIXED);
    }

    /** @return 最大重试次数 */
    public int getMaxRetries() { return maxRetries; }

    /** @return 基础延迟（ms） */
    public long getBaseDelayMs() { return baseDelayMs; }

    /** @return 固定延迟（ms） */
    public long getFixedDelayMs() { return fixedDelayMs; }

    /** @return 退避策略 */
    public BackoffStrategy getStrategy() { return strategy; }
}