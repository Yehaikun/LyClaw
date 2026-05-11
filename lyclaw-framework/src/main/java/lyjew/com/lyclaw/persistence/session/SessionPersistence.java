package lyjew.com.lyclaw.persistence.session;

import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.persistence.PersistenceDecision;

/**
 * 会话持久化策略接口，定义会话数据的写入评估逻辑。
 *
 * <p>该接口的实现类负责评估当前会话是否应该被持久化到存储中。
 * 与 {@link lyjew.com.lyclaw.persistence.memory.MemoryPersistence} 类似，
 * 会话持久化也采用「写前评估」模式，根据对话轮次和距上次写入的时间间隔
 * 来决定写入策略。</p>
 *
 * <p>该接口提供两个评估入口：</p>
 * <ul>
 *   <li>{@link #evaluate(Session, int, long)} - 常规写入评估，在每次交互后调用</li>
 *   <li>{@link #evaluateOnClose(Session)} - 会话关闭时的评估，通常应强制写入</li>
 * </ul>
 */
public interface SessionPersistence {

    /**
     * 评估当前会话是否需要持久化写入。
     *
     * @param session              当前会话对象
     * @param turnCount            当前会话的对话轮次数
     * @param millisSinceLastWrite 距上次写入的毫秒数
     * @return 持久化决策，指示应立即写入、推迟还是跳过
     */
    PersistenceDecision evaluate(Session session, int turnCount, long millisSinceLastWrite);

    /**
     * 会话关闭时的持久化评估。
     * 通常实现为强制写入，以确保会话数据不会丢失。
     *
     * @param session 当前会话对象
     * @return 持久化决策
     */
    PersistenceDecision evaluateOnClose(Session session);
}
