package lyjew.com.lyclaw.error;

/**
 * 重试配置，定义工具调用或 API 请求失败时的重试策略。
 *
 * <p>支持三种退避策略：FIXED（固定间隔）、EXPONENTIAL（指数退避）、LINEAR（线性退避）。
 * 提供静态工厂方法快速创建常用配置。</p>
 */
public class RetryConfig {

    /** 退避策略枚举 */
    public enum BackoffStrategy {
        /** 固定间隔重试 */
        FIXED,
        /** 指数退避，每次重试间隔翻倍 */
        EXPONENTIAL,
        /** 线性退避，每次重试间隔线性增加 */
        LINEAR
    }

    /** 最大重试次数 */
    private final int maxRetries;
    /** 指数/线性退避的基础延迟（毫秒） */
    private final long baseDelayMs;
    /** 固定退避的延迟（毫秒） */
    private final long fixedDelayMs;
    /** 当前使用的退避策略 */
    private final BackoffStrategy strategy;

    public RetryConfig(int maxRetries, long baseDelayMs,
                       long fixedDelayMs, BackoffStrategy strategy) {
        this.maxRetries = maxRetries;
        this.baseDelayMs = baseDelayMs;
        this.fixedDelayMs = fixedDelayMs;
        this.strategy = strategy;
    }

    public static RetryConfig exponential(int maxRetries, long baseDelayMs) {
        return new RetryConfig(maxRetries, baseDelayMs, 0, BackoffStrategy.EXPONENTIAL);
    }

    public static RetryConfig fixed(int maxRetries, long fixedDelayMs) {
        return new RetryConfig(maxRetries, 0, fixedDelayMs, BackoffStrategy.FIXED);
    }

    public int getMaxRetries() { return maxRetries; }

    public long getBaseDelayMs() { return baseDelayMs; }

    public long getFixedDelayMs() { return fixedDelayMs; }

    public BackoffStrategy getStrategy() { return strategy; }
}
