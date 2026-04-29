package lyjew.com.lyclaw.transaction.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.transaction.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 默认事务管理器 —— 基于快照的事务实现。
 *
 * <p><b>事务流程</b>：
 * <pre>
 * begin(sessionId) → 创建 TransactionContext，记录开始时间
 *     createSnapshot(context) → 捕获当前状态为 List<SessionUpdate>
 * commit(sessionId) → 标记状态为 COMMITTED
 * rollback(sessionId) → 恢复快照，标记状态为 ROLLED_BACK
 * </pre>
 * </p>
 *
 * <p><b>设计动机</b>：每次 AI 对话可能产生多个变更（追加消息、写入记忆、修改配置），
 * 这些变更需要作为一个整体要么全部成功要么全部回滚。
 * 事务管理器确保变更的原子性，避免部分变更造成的状态不一致。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see SessionTransaction
 * @see TransactionContext
 */
@Component
public class DefaultSessionTransaction implements SessionTransaction {

    /**
     * 当前活跃的事务上下文映射 —— sessionId → TransactionContext。
     */
    private final ConcurrentHashMap<String, TransactionContext> activeTransactions = new ConcurrentHashMap<>();

    @Override
    public void begin(String sessionId, String userId) {
        // TransactionContext 构造器：(transactionId, sessionId, contextSnapshot)
        // 第三个参数是 String（快照摘要），不是 List<SessionUpdate>
        TransactionContext context = new TransactionContext(
                UUID.randomUUID().toString(),
                sessionId,
                "snapshot: messages=" + 0
        );
        activeTransactions.put(sessionId, context);
    }

    @Override
    public boolean commit(String sessionId) {
        TransactionContext context = activeTransactions.get(sessionId);
        if (context == null) return false;
        context.setStatus(TransactionContext.STATUS_COMMITTED);
        activeTransactions.remove(sessionId);
        return true;
    }

    @Override
    public boolean rollback(String sessionId) {
        TransactionContext context = activeTransactions.get(sessionId);
        if (context == null) return false;
        context.setStatus(TransactionContext.STATUS_ROLLED_BACK);
        activeTransactions.remove(sessionId);
        return true;
    }

    @Override
    public String getStatus(String sessionId) {
        TransactionContext context = activeTransactions.get(sessionId);
        if (context == null) return "NONE";
        return context.getStatus();
    }

    @Override
    public List<SessionUpdate> createSnapshot(String sessionId, ChatContext context) {
        // SessionUpdate 构造器：(sessionId, updateType, oldValue, newValue, operator, timestamp)
        // oldValue/newValue 是 String（JSON），不能传 List<Message>
        List<SessionUpdate> updates = new ArrayList<>();
        int msgCount = context.getRequest().getMessages().size();
        updates.add(new SessionUpdate(
                sessionId,
                "MESSAGE_SNAPSHOT",
                "[]",
                "{\"count\":" + msgCount + "}",
                "system",
                Instant.now()
        ));
        return updates;
    }
}