package lyjew.com.lyclaw.persistence;

import java.util.Objects;

/**
 * 持久化决策实体，封装了持久化策略的评估结果。
 *
 * <p>当持久化层收到一次写入请求时，会通过策略评估返回一个
 * {@code PersistenceDecision}，指示应执行写入（WRITE）、推迟写入（DEFER）
 * 还是跳过写入（SKIP）。每个决策都附带原因说明，便于调试和审计。</p>
 *
 * <p>该类是不可变的值对象（value object），通过静态工厂方法构造：</p>
 * <ul>
 *   <li>{@link #write(String)} - 立即写入</li>
 *   <li>{@link #defer(String)} - 延迟到后续批次写入</li>
 *   <li>{@link #skip(String)} - 跳过本次写入</li>
 * </ul>
 */
public final class PersistenceDecision {

    /** 持久化信号 */
    private final PersistenceSignal signal;
    /** 决策原因说明 */
    private final String reason;

    /**
     * 私有构造器，确保通过静态工厂方法创建实例。
     *
     * @param signal 持久化信号，不可为 null
     * @param reason 决策原因，可为 null
     */
    private PersistenceDecision(PersistenceSignal signal, String reason) {
        this.signal = Objects.requireNonNull(signal, "signal must not be null");
        this.reason = reason;
    }

    /**
     * 创建一个「立即写入」的持久化决策。
     *
     * @param reason 写入原因
     * @return 信号为 WRITE 的决策实例
     */
    public static PersistenceDecision write(String reason) {
        return new PersistenceDecision(PersistenceSignal.WRITE, reason);
    }

    /**
     * 创建一个「推迟写入」的持久化决策。
     *
     * @param reason 推迟原因
     * @return 信号为 DEFER 的决策实例
     */
    public static PersistenceDecision defer(String reason) {
        return new PersistenceDecision(PersistenceSignal.DEFER, reason);
    }

    /**
     * 创建一个「跳过写入」的持久化决策。
     *
     * @param reason 跳过原因
     * @return 信号为 SKIP 的决策实例
     */
    public static PersistenceDecision skip(String reason) {
        return new PersistenceDecision(PersistenceSignal.SKIP, reason);
    }

    /**
     * 判断该决策是否表示应执行写入操作。
     *
     * @return true 表示应立即持久化
     */
    public boolean shouldWrite() { return signal == PersistenceSignal.WRITE; }

    /** @return 持久化信号枚举值 */
    public PersistenceSignal signal() { return signal; }

    /** @return 决策原因说明 */
    public String reason() { return reason; }

    // equals/hashCode/toString 使用 signal 和 reason 作为相等性依据
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PersistenceDecision that)) return false;
        return signal == that.signal && Objects.equals(reason, that.reason);
    }

    @Override
    public int hashCode() { return Objects.hash(signal, reason); }

    @Override
    public String toString() {
        return "PersistenceDecision{" + signal + (reason != null && !reason.isEmpty() ? ", reason='" + reason + '\'' : "") + '}';
    }
}
