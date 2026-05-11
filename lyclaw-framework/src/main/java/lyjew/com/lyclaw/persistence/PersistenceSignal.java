package lyjew.com.lyclaw.persistence;

/**
 * 持久化信号枚举，表示持久化层对一次写入请求的处理指示。
 *
 * <ul>
 *   <li>{@code WRITE} - 立即执行持久化写入</li>
 *   <li>{@code DEFER} - 推迟写入，通常因为积累的数据量不够或距离上次写入时间太近，
 *       待后续批次再一起写入</li>
 *   <li>{@code SKIP} - 跳过本次写入，通常因为没有有意义的数据需要持久化</li>
 * </ul>
 */
public enum PersistenceSignal {
    /** 立即写入 */
    WRITE,
    /** 推迟写入 */
    DEFER,
    /** 跳过写入 */
    SKIP
}
