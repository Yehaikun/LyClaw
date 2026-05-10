package lyjew.com.lyclaw.persistence;

/**
 * 持久化决策信号枚举。
 *
 * <p>由 {@link PersistenceDecision} 包装后返回给调用方。
 * 三个信号分别对应：立即落盘 / 暂缓积累 / 不需要落盘。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see PersistenceDecision
 */
public enum PersistenceSignal {

    /** 立即落盘 */
    WRITE,

    /** 暂缓，积累更多后再落盘 */
    DEFER,

    /** 不需要落盘（如空内容、无需持久化的场景） */
    SKIP
}
