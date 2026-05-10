package lyjew.com.lyclaw.persistence.session;

import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.persistence.PersistenceDecision;

/**
 * 会话持久化策略接口。
 *
 * <p>只负责返回"该不该存"的决策，不持有任何存储引用。
 * 实现类是纯函数：输入 Session 和状态值，输出 {@link PersistenceDecision}。</p>
 *
 * <p><b>设计动机</b>：将"何时持久化会话"的决策逻辑从 ResponseBuildStage 中独立出来，
 * 使得持久化时机可配置、可替换、可扩展。</p>
 *
 * <p><b>设计模式</b>：策略模式</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see EveryTurnSessionPersistence
 * @see PersistenceDecision
 */
public interface SessionPersistence {

    /**
     * 一次对话轮次完成后的持久化决策。
     *
     * @param session              当前会话
     * @param turnCount            本会话当前轮数（从 1 开始）
     * @param millisSinceLastWrite 距上次写入的毫秒数，-1 表示从未写入过
     * @return WRITE = 立即落盘 / DEFER = 暂存 / SKIP = 不需要
     */
    PersistenceDecision evaluate(Session session, int turnCount, long millisSinceLastWrite);

    /**
     * 会话关闭（超时或手动关闭）时的持久化决策。
     * 通常应返回 WRITE，确保关断时不丢数据。
     */
    PersistenceDecision evaluateOnClose(Session session);
}
