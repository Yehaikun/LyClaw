package lyjew.com.lyclaw.error;

public class RetryConfig {

    public enum BackoffStrategy {
        FIXED,
        EXPONENTIAL,
        LINEAR
    }

    private final int maxRetries;
    private final long baseDelayMs;
    private final long fixedDelayMs;
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
