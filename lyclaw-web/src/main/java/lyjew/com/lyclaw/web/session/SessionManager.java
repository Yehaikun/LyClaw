package lyjew.com.lyclaw.web.session;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lyjew.com.lyclaw.config.StorageProperties;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.persistence.SessionFactory;
import lyjew.com.lyclaw.persistence.queue.AsyncWriteQueue;
import lyjew.com.lyclaw.persistence.queue.AsyncWriteQueueRegistry;
import lyjew.com.lyclaw.persistence.repository.SessionRepository;

/**
 * 会话生命周期管理器——ChatController、SubagentSpawner、ContextPruningScheduler
 * 等所有需要访问会话的组件的唯一入口。
 *
 * 维护活跃会话的ConcurrentHashMap缓存。{@link #getSession}在缓存未命中时
 * 从SQLite+JSONL懒加载重建Session对象。{@link #createSession}创建新会话时
 * 同时初始化JSONL文件和SQLite记录。
 *
 * 同时实现{@link SessionFactory}（供framework层SubagentSpawner使用）
 * 和持久化钩子{@code onMessage()}（作为ReActMessageHook注册到ToolCallLoop）。
 */
public class SessionManager implements SessionFactory {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final SessionRepository sessionRepository;
    private final AsyncWriteQueueRegistry queueRegistry;
    private final StorageProperties storageProperties;
    private final ConcurrentHashMap<String, Session> activeSessions = new ConcurrentHashMap<>();

    public SessionManager(SessionRepository sessionRepository,
                          AsyncWriteQueueRegistry queueRegistry,
                          StorageProperties storageProperties) {
        this.sessionRepository = sessionRepository;
        this.queueRegistry = queueRegistry;
        this.storageProperties = storageProperties;
    }

    /**
     * 创建新会话——生成sessionId，初始化JSONL+SQLite，注册异步队列，加入缓存。
     */
    public Session createSession(String agentId, String model) {
        int idLen = storageProperties.getSession().getIdLength();
        String sessionId = UUID.randomUUID().toString().substring(0, idLen);
        String filePath = buildFilePath(agentId, sessionId);

        String defaultName = "Chat " + java.time.format.DateTimeFormatter
                .ofPattern("MM-dd HH:mm").format(LocalDateTime.now());

        Session session = Session.builder()
                .sessionId(sessionId)
                .name(defaultName)
                .agentId(agentId)
                .model(model)
                .filePath(filePath)
                .messageIndex(0)
                .compactionCount(0)
                .heartbeatMode(false)
                .messages(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        sessionRepository.create(session);

        int queueCap = storageProperties.getSession().getWriteQueueCapacity();
        queueRegistry.getOrCreate(sessionId,
                () -> new AsyncWriteQueue(sessionId, sessionRepository, queueCap));

        activeSessions.put(sessionId, session);
        log.debug("创建会话: sessionId={}, agentId={}", sessionId, agentId);
        return session;
    }

    /**
     * 获取会话——先查缓存，未命中从SQLite+JSONL懒加载重建。
     *
     * 加载步骤：
     * 1. 查SQLite sessions表获取元数据（filePath、agentId等）
     * 2. 读JSONL首行获取session_created信息
     * 3. 分页读最近N条消息到内存
     * 4. 重建Session对象并注册到activeSessions缓存
     */
    public Session getSession(String sessionId) {
        Session cached = activeSessions.get(sessionId);
        if (cached != null) return cached;

        List<Map<String, Object>> rows = sessionRepository.findBySessionId(sessionId);
        if (rows.isEmpty()) return null;
        Map<String, Object> meta = rows.get(0);

        String filePath = (String) meta.get("file_path");
        Map<String, Object> firstLine = sessionRepository.readFirstLine(filePath);

        int totalCount = meta.get("message_count") instanceof Number n ? n.intValue() : 0;
        int pageSize = storageProperties.getSession().getPageSize();
        List<Map<String, Object>> recentLines = sessionRepository.readMessages(
                filePath, -1, pageSize);

        Session session = Session.builder()
                .sessionId(sessionId)
                .name((String) meta.get("name"))
                .agentId((String) meta.get("agent_id"))
                .filePath(filePath)
                .messageIndex(totalCount)
                .compactionCount(meta.get("compaction_count") instanceof Number n ? n.intValue() : 0)
                .parentSessionId((String) meta.get("parent_session_id"))
                .parentAgentId((String) meta.get("parent_agent_id"))
                .heartbeatMode(false)
                .messages(convertJsonlToMessages(recentLines))
                .createdAt(epochToLocalDateTime((Long) meta.get("created_at")))
                .updatedAt(epochToLocalDateTime((Long) meta.get("updated_at")))
                .build();

        int queueCap = storageProperties.getSession().getWriteQueueCapacity();
        queueRegistry.getOrCreate(sessionId,
                () -> new AsyncWriteQueue(sessionId, sessionRepository, queueCap));
        activeSessions.put(sessionId, session);

        log.debug("懒加载会话完成: sessionId={}, agentId={}, messageCount={}",
                sessionId, session.getAgentId(), totalCount);
        return session;
    }

    // ── ReActMessageHook 语义方法 ─────────────────────────

    /**
     * 持久化钩子——ReAct循环每产生一条消息时回调。
     * 内部：更新Session内存状态 → Message→JSONL字段 → AsyncWriteQueue.enqueue() → 立即返回。
     */
    public void onMessage(Session session, Message message) {
        if (session == null || session.isHeartbeatSession()) return;

        session.addMessage(message);

        Map<String, Object> fields = messageToJsonlFields(session, message);
        appendMessage(session, fields);

        if ("user".equals(message.getRole()) && session.getMessageIndex() == 1) {
            String preview = message.getContent();
            int maxLen = storageProperties.getSession().getPreviewMaxLength();
            if (preview != null && preview.length() > maxLen) {
                preview = preview.substring(0, maxLen);
            }
            sessionRepository.updateFirstMsgPreview(session.getSessionId(), preview);
            // 同时将会话名称更新为首条消息预览
            String name = preview != null ? preview : session.getName();
            session.setName(name);
            sessionRepository.updateName(session.getSessionId(), name);
        }
    }

    // ── SessionFactory 实现 ────────────────────────────────

    @Override
    public Session createSubagentSession(String parentSessionId, String parentAgentId,
                                          String childAgentId, String model) {
        int idLen = storageProperties.getSession().getIdLength();
        String sessionId = UUID.randomUUID().toString().substring(0, idLen);
        String filePath = buildFilePath(childAgentId, sessionId);

        Session session = Session.builder()
                .sessionId(sessionId)
                .agentId(childAgentId)
                .model(model)
                .filePath(filePath)
                .parentSessionId(parentSessionId)
                .parentAgentId(parentAgentId)
                .messageIndex(0)
                .compactionCount(0)
                .heartbeatMode(false)
                .messages(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        sessionRepository.create(session);
        int queueCap = storageProperties.getSession().getWriteQueueCapacity();
        queueRegistry.getOrCreate(sessionId,
                () -> new AsyncWriteQueue(sessionId, sessionRepository, queueCap));
        activeSessions.put(sessionId, session);
        return session;
    }

    @Override
    public int getActiveCount(String agentId) {
        return (int) activeSessions.values().stream()
                .filter(s -> agentId.equals(s.getAgentId()))
                .count();
    }

    // ── 内部方法 ───────────────────────────────────────────

    /** 追加消息到异步写入队列（不阻塞调用者） */
    private void appendMessage(Session session, Map<String, Object> messageFields) {
        int queueCap = storageProperties.getSession().getWriteQueueCapacity();
        AsyncWriteQueue queue = queueRegistry.getOrCreate(session.getSessionId(),
                () -> new AsyncWriteQueue(session.getSessionId(), sessionRepository, queueCap));
        queue.enqueue(session, messageFields);
    }

    /** 心跳会话——不持久化，不注册队列，不加入activeSessions */
    public Session createHeartbeatSession(String agentId) {
        return Session.builder()
                .sessionId("heartbeat-" + UUID.randomUUID().toString().substring(0,
                        storageProperties.getSession().getIdLength()))
                .agentId(agentId)
                .heartbeatMode(true)
                .messages(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /** 删除会话——直接从SQLite获取filePath，清理JSONL文件+SQLite记录+缓存+异步队列。
     *  不依赖缓存命中，服务器重启后也能正确删除。 */
    public void deleteSession(String sessionId) {
        // 1. 从缓存移除（如果存在）
        Session cached = activeSessions.remove(sessionId);
        // 2. 从SQLite获取filePath——缓存只是加速，SQLite才是真相
        String filePath;
        if (cached != null && !cached.isHeartbeatSession()) {
            filePath = cached.getFilePath();
        } else {
            List<Map<String, Object>> rows = sessionRepository.findBySessionId(sessionId);
            if (rows.isEmpty()) {
                log.warn("deleteSession: session {} 不存在", sessionId);
                return;
            }
            filePath = (String) rows.get(0).get("file_path");
        }
        // 3. 清理持久化数据和队列
        sessionRepository.delete(sessionId, filePath);
        queueRegistry.remove(sessionId);
        log.debug("删除会话完成: sessionId={}", sessionId);
    }

    /** 获取所有活跃会话（供ContextPruningScheduler遍历） */
    public Map<String, Session> getActiveSessions() {
        return Collections.unmodifiableMap(activeSessions);
    }

    private String buildFilePath(String agentId, String sessionId) {
        return storageProperties.getBasePath() + "/agents/"
                + agentId + "/sessions/" + sessionId + ".jsonl";
    }

    private Map<String, Object> messageToJsonlFields(Session session, Message msg) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if ("tool".equals(msg.getRole())) {
            fields.put("type", "tool_result");
        } else {
            fields.put("type", "message");
        }
        fields.put("role", msg.getRole());
        fields.put("content", msg.getContent());
        fields.put("timestamp", System.currentTimeMillis());
        fields.put("messageIndex", session.getMessageIndex());

        if (msg.getToolCallId() != null) fields.put("toolCallId", msg.getToolCallId());
        if (msg.getToolName() != null) fields.put("toolName", msg.getToolName());
        if (msg.getModel() != null) fields.put("model", msg.getModel());
        if (msg.getUsage() != null) fields.put("usage", msg.getUsage());
        if (msg.getToolCalls() != null) fields.put("toolCalls", msg.getToolCalls());
        if (msg.getThinking() != null) fields.put("thinking", msg.getThinking());

        return fields;
    }

    private List<Message> convertJsonlToMessages(List<Map<String, Object>> lines) {
        List<Message> messages = new ArrayList<>();
        for (Map<String, Object> line : lines) {
            String type = (String) line.get("type");
            if ("message".equals(type) || "tool_result".equals(type)) {
                messages.add(mapToMessage(line));
            }
        }
        return messages;
    }

    private Message mapToMessage(Map<String, Object> fields) {
        return Message.builder()
                .role((String) fields.get("role"))
                .content((String) fields.get("content"))
                .toolCallId((String) fields.get("toolCallId"))
                .toolName((String) fields.get("toolName"))
                .model((String) fields.get("model"))
                .thinking((String) fields.get("thinking"))
                .build();
    }

    private LocalDateTime epochToLocalDateTime(Long epochMillis) {
        return epochMillis != null
                ? LocalDateTime.ofEpochSecond(epochMillis / 1000, 0, ZoneOffset.UTC)
                : LocalDateTime.now();
    }
}
