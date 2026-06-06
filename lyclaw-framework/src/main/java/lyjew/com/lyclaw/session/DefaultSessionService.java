package lyjew.com.lyclaw.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.SessionQuery;
import lyjew.com.lyclaw.model.SessionStatus;

/**
 * 默认会话服务实现 —— 组合 SessionStore + MessageStore + VariableStore + WritePolicy。
 *
 * <p>所有消息操作通过写策略控制持久化时机，避免频繁 I/O。
 * 上下文构建时应用 ContextPolicy 裁剪消息列表。</p>
 */
public class DefaultSessionService implements SessionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultSessionService.class);

    private final SessionStore sessionStore;
    private final MessageStore messageStore;
    private final VariableStore variableStore;
    private final SessionWritePolicy writePolicy;
    private final ContextPolicy defaultContextPolicy;

    // sessionId → write state
    private final ConcurrentHashMap<String, SessionWriteState> writeStates = new ConcurrentHashMap<>();

    public DefaultSessionService(SessionStore sessionStore,
                                  MessageStore messageStore,
                                  VariableStore variableStore) {
        this(sessionStore, messageStore, variableStore,
                new ImmediateWritePolicy(), new SlidingWindowPolicy());
    }

    public DefaultSessionService(SessionStore sessionStore,
                                  MessageStore messageStore,
                                  VariableStore variableStore,
                                  SessionWritePolicy writePolicy,
                                  ContextPolicy defaultContextPolicy) {
        this.sessionStore = sessionStore;
        this.messageStore = messageStore;
        this.variableStore = variableStore;
        this.writePolicy = writePolicy != null ? writePolicy : new ImmediateWritePolicy();
        this.defaultContextPolicy = defaultContextPolicy != null ? defaultContextPolicy : new SlidingWindowPolicy();
    }

    // ── 会话元数据 ──

    @Override
    public Session getOrCreate(String sessionId, String agentId, String model) {
        return sessionStore.getOrCreate(sessionId, agentId, model);
    }

    @Override
    public Session create(String agentId, String model) {
        return sessionStore.createSession(agentId, model);
    }

    @Override
    public Optional<Session> get(String sessionId) {
        return sessionStore.getSession(sessionId);
    }

    @Override
    public void update(String sessionId, SessionUpdate update) {
        if (update == null || !update.hasChanges()) return;
        Optional<Session> existing = sessionStore.getSession(sessionId);
        if (existing.isEmpty()) {
            log.warn("更新不存在的会话: {}", sessionId);
            return;
        }
        Session session = existing.get();
        boolean changed = false;
        if (update.getName() != null) { session.setName(update.getName()); changed = true; }
        if (update.getModel() != null) { session.setModel(update.getModel()); changed = true; }
        if (update.getStatus() != null) { session.setStatus(update.getStatus()); changed = true; }
        if (update.getTags() != null) { session.setTags(update.getTags()); changed = true; }
        if (update.getMetadataJson() != null) { session.setMetadataJson(update.getMetadataJson()); changed = true; }
        if (changed) {
            session.setUpdatedAt(System.currentTimeMillis());
            sessionStore.save(session);
        }
    }

    @Override
    public void delete(String sessionId) {
        messageStore.deleteBySession(sessionId);
        variableStore.clear(sessionId);
        sessionStore.deleteSession(sessionId);
        writeStates.remove(sessionId);
    }

    @Override
    public void markStatus(String sessionId, SessionStatus status) {
        sessionStore.getSession(sessionId).ifPresent(session -> {
            session.setStatus(status);
            session.setUpdatedAt(System.currentTimeMillis());
            sessionStore.save(session);
        });
    }

    @Override
    public List<Session> list(SessionQuery query) {
        return sessionStore.list(query);
    }

    @Override
    public int count(SessionQuery query) {
        return sessionStore.count(query);
    }

    // ── 消息操作 ──

    @Override
    public void appendMessage(String sessionId, Message message) {
        messageStore.append(sessionId, message);
        // 更新会话活跃时间
        sessionStore.getSession(sessionId).ifPresent(s -> {
            s.setLastActiveAt(System.currentTimeMillis());
            s.setMessageCount(s.getMessageCount() + 1);
            s.setUpdatedAt(System.currentTimeMillis());
            sessionStore.save(s);
        });
        // 写策略决定是否 flush
        flushIfNeeded(sessionId);
    }

    @Override
    public void appendMessages(String sessionId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;
        messageStore.appendBatch(sessionId, messages);
        sessionStore.getSession(sessionId).ifPresent(s -> {
            s.setLastActiveAt(System.currentTimeMillis());
            s.setMessageCount(s.getMessageCount() + messages.size());
            s.setUpdatedAt(System.currentTimeMillis());
            sessionStore.save(s);
        });
        flushIfNeeded(sessionId);
    }

    @Override
    public List<Message> loadMessages(String sessionId, int offset, int limit) {
        return messageStore.load(sessionId, offset, limit);
    }

    @Override
    public List<Message> loadLatestMessages(String sessionId, int lastN) {
        return messageStore.loadLatest(sessionId, lastN);
    }

    @Override
    public int messageCount(String sessionId) {
        return messageStore.count(sessionId);
    }

    // ── 会话变量 ──

    @Override
    public void setVariable(String sessionId, String key, Object value) {
        variableStore.set(sessionId, key, value);
    }

    @Override
    public void setVariables(String sessionId, Map<String, Object> values) {
        variableStore.setAll(sessionId, values);
    }

    @Override
    public <T> Optional<T> getVariable(String sessionId, String key, Class<T> type) {
        return variableStore.get(sessionId, key, type);
    }

    @Override
    public Map<String, Object> getAllVariables(String sessionId) {
        return variableStore.getAll(sessionId);
    }

    @Override
    public void clearVariables(String sessionId) {
        variableStore.clear(sessionId);
    }

    // ── 上下文构建 ──

    @Override
    public List<Message> buildContext(String sessionId, ContextPolicy policy) {
        List<Message> allMessages = messageStore.loadLatest(sessionId, 1000);
        ContextPolicy effectivePolicy = policy != null ? policy : defaultContextPolicy;
        return effectivePolicy.prune(sessionId, allMessages, null);
    }

    @Override
    public void flush(String sessionId) {
        // 当前 InMemory 实现下不需要显式 flush（始终在内存中）。
        // SQLite 等持久化实现通过 WritePolicy 控制落盘时机。
        SessionWriteState state = writeStates.get(sessionId);
        if (state != null) {
            state.onFlushed();
        }
    }

    // ── 内部方法 ──

    private void flushIfNeeded(String sessionId) {
        SessionWriteState state = writeStates.computeIfAbsent(sessionId, SessionWriteState::new);
        state.onMessageAppended();
        if (writePolicy.shouldFlush(state)) {
            state.onFlushed();
        }
    }
}
