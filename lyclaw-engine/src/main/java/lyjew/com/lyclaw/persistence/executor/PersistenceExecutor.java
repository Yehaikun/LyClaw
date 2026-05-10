package lyjew.com.lyclaw.persistence.executor;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.MemoryManager;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.persistence.PersistenceDecision;
import lyjew.com.lyclaw.storage.SessionStorage;
import org.springframework.stereotype.Component;

/**
 * 持久化执行器。
 *
 * <p>系统中唯一负责"将决策映射到存储操作"的组件。
 * 只认识存储接口，不认识任何策略。
 * 策略返回 {@link PersistenceDecision}，执行器根据决策调用对应的存储操作。</p>
 *
 * <p><b>单一职责</b>：执行，不管决策。</p>
 *
 * <p><b>设计模式</b>：命令模式 —— 将"写入操作"封装为可执行的命令，
 * 决策层不知道如何执行，执行层不知道如何决策。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see SessionPersistence
 * @see MemoryPersistence
 */
@Slf4j
@Component
public class PersistenceExecutor {

    private final SessionStorage sessionStorage;
    private final MemoryManager memoryManager;

    public PersistenceExecutor(SessionStorage sessionStorage, MemoryManager memoryManager) {
        this.sessionStorage = sessionStorage;
        this.memoryManager = memoryManager;
    }

    /**
     * 执行会话写入决策。如果决策为 WRITE，则调用 sessionStorage.save()。
     *
     * @param session  当前会话
     * @param decision 持久化决策
     */
    public void executeSessionWrite(Session session, PersistenceDecision decision) {
        if (decision.shouldWrite()) {
            log.debug("  [PersistenceExecutor] 会话写入: decision={}", decision);
            sessionStorage.save(session);
        } else {
            log.debug("  [PersistenceExecutor] 会话暂缓写入: decision={}", decision);
        }
    }

    /**
     * 执行记忆刷盘决策。如果决策为 WRITE，则调用 memoryManager.flush()。
     *
     * @param decision 持久化决策
     */
    public void executeMemoryFlush(PersistenceDecision decision) {
        if (decision.shouldWrite()) {
            log.debug("  [PersistenceExecutor] 记忆刷盘: decision={}", decision);
            memoryManager.flush();
        } else {
            log.debug("  [PersistenceExecutor] 记忆暂缓刷盘: decision={}", decision);
        }
    }
}
