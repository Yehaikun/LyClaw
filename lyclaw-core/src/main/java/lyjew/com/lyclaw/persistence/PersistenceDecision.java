package lyjew.com.lyclaw.persistence;

import java.util.Objects;

/**
 * 持久化决策结果。
 *
 * <p>纯值对象，不可变。通过工厂方法创建，屏蔽内部构造函数细节。</p>
 *
 * <p>设计模式：值对象模式<br>
 * 用途：连接策略层和执行层的纯数据载体。策略接口返回 PersistenceDecision，
 * 执行器接收 PersistenceDecision 决定是否刷盘。双方互不感知对方的存在。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see PersistenceSignal
 */
public final class PersistenceDecision {

    private final PersistenceSignal signal;
    private final String reason;

    private PersistenceDecision(PersistenceSignal signal, String reason) {
        this.signal = Objects.requireNonNull(signal, "signal must not be null");
        this.reason = reason;
    }

    // ========== 工厂方法 ==========

    /** 立即落盘 */
    public static PersistenceDecision write(String reason) {
        return new PersistenceDecision(PersistenceSignal.WRITE, reason);
    }

    /** 暂缓，积累更多后再落盘 */
    public static PersistenceDecision defer(String reason) {
        return new PersistenceDecision(PersistenceSignal.DEFER, reason);
    }

    /** 不需要落盘 */
    public static PersistenceDecision skip(String reason) {
        return new PersistenceDecision(PersistenceSignal.SKIP, reason);
    }

    // ========== 快捷方法 ==========

    /** 当前决策是否需要执行写入 */
    public boolean shouldWrite() {
        return signal == PersistenceSignal.WRITE;
    }

    // ========== getters ==========

    public PersistenceSignal signal() {
        return signal;
    }

    public String reason() {
        return reason;
    }

    @Override
    public String toString() {
        return "PersistenceDecision{" + signal + (reason != null && !reason.isEmpty() ? ", reason='" + reason + '\'' : "") + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PersistenceDecision that)) return false;
        return signal == that.signal && Objects.equals(reason, that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(signal, reason);
    }
}
