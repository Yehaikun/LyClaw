package lyjew.com.lyclaw.session;

/**
 * 会话写策略 SPI —— 控制消息持久化的频率和时机。
 *
 * <p>避免每次 append 都立即落盘，支持按消息数/时间间隔/轮次边界批量写入。
 * 默认实现 {@link ImmediateWritePolicy} 每条消息立即写（行为与旧版一致）。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * // 每 20 条消息或间隔 30 秒批量写入一次
 * SessionWritePolicy policy = new ThresholdWritePolicy(20, 30_000);
 * }</pre>
 */
public interface SessionWritePolicy {

    /**
     * 评估当前的写入状态，决定是否需要持久化。
     *
     * @param state 当前写状态（累计消息数、时间等）
     * @return true 表示应执行持久化写入
     */
    boolean shouldFlush(SessionWriteState state);

    /** 获取策略名称 */
    default String name() {
        return getClass().getSimpleName();
    }
}
