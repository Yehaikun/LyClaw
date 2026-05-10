package lyjew.com.lyclaw.persistence.session.impl;

import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.persistence.PersistenceDecision;
import lyjew.com.lyclaw.persistence.session.SessionPersistence;
import org.springframework.stereotype.Component;

/**
 * 每轮持久化策略 —— 每次对话轮次结束后立即写入会话文件。
 *
 * <p>当前 FileMemoryManager 的默认行为（每次 append 都 persist），
 * 作为迁移到持久化决策层后的默认策略，保持行为不变。</p>
 *
 * <p><b>适用场景</b>：对数据安全性要求高、允许高频写入的环境。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see SessionPersistence
 */
@Component
public class EveryTurnSessionPersistence implements SessionPersistence {

    @Override
    public PersistenceDecision evaluate(Session session, int turnCount, long millisSinceLastWrite) {
        return PersistenceDecision.write("每轮都存 (turn=" + turnCount + ")");
    }

    @Override
    public PersistenceDecision evaluateOnClose(Session session) {
        return PersistenceDecision.write("会话关闭，强制写入");
    }
}
