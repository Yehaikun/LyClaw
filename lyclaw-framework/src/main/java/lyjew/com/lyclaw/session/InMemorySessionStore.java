package lyjew.com.lyclaw.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;

/**
 * 默认进程内 {@link SessionStore} 实现。
 *
 * <p>该实现适合 demo、单元测试和单进程临时运行，进程重启后数据会丢失。</p>
 */
public class InMemorySessionStore implements SessionStore {

    private static final int DEFAULT_MESSAGE_LIMIT = 500;

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public Session createSession(String agentId, String model) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        Session session = Session.builder()
                .sessionId(sessionId)
                .name(agentId != null && !agentId.isBlank() ? agentId : "Chat")
                .model(model)
                .messages(new ArrayList<>())
                .build();
        sessions.put(sessionId, session);
        return copySession(session);
    }

    @Override
    public Session getOrCreate(String sessionId, String agentId, String model) {
        if (sessionId == null || sessionId.isBlank()) {
            return createSession(agentId, model);
        }
        Session session = sessions.computeIfAbsent(sessionId, id -> Session.builder()
                .sessionId(id)
                .name(agentId != null && !agentId.isBlank() ? agentId : "Chat")
                .model(model)
                .messages(new ArrayList<>())
                .build());
        return copySession(session);
    }

    @Override
    public Optional<Session> getSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(sessionId)).map(this::copySession);
    }

    @Override
    public void save(Session session) {
        if (session == null || session.getSessionId() == null || session.getSessionId().isBlank()) {
            return;
        }
        sessions.put(session.getSessionId(), copySession(session));
    }

    @Override
    public void deleteSession(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    @Override
    public List<Message> loadMessages(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        Session session = sessions.get(sessionId);
        if (session == null || session.getMessages() == null || session.getMessages().isEmpty()) {
            return List.of();
        }
        int effectiveLimit = limit > 0 ? limit : DEFAULT_MESSAGE_LIMIT;
        List<Message> messages = session.getMessages();
        int from = Math.max(0, messages.size() - effectiveLimit);
        return new ArrayList<>(messages.subList(from, messages.size()));
    }

    @Override
    public void saveMessages(String sessionId, List<Message> messages) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Session session = sessions.computeIfAbsent(sessionId, id -> Session.builder()
                .sessionId(id)
                .name("Chat")
                .messages(new ArrayList<>())
                .build());
        List<Message> copy = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
        if (copy.size() > DEFAULT_MESSAGE_LIMIT) {
            copy = new ArrayList<>(copy.subList(copy.size() - DEFAULT_MESSAGE_LIMIT, copy.size()));
        }
        session.setMessages(copy);
    }

    @Override
    public List<Map<String, Object>> listSessions(String agentId) {
        return sessions.values().stream()
                .filter(session -> agentId == null || agentId.isBlank()
                        || agentId.equals(session.getName()))
                .sorted(Comparator.comparing(Session::getSessionId).reversed())
                .map(session -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sessionId", session.getSessionId());
                    row.put("session_id", session.getSessionId());
                    row.put("name", session.getName());
                    row.put("agentId", session.getName());
                    row.put("agent_id", session.getName());
                    row.put("model", session.getModel());
                    row.put("messageCount", session.getMessages() != null ? session.getMessages().size() : 0);
                    row.put("message_count", session.getMessages() != null ? session.getMessages().size() : 0);
                    row.put("createdAt", "");
                    row.put("updatedAt", "");
                    row.put("created_at", 0L);
                    row.put("updated_at", 0L);
                    row.put("firstMsgPreview", firstUserPreview(session));
                    row.put("first_msg_preview", firstUserPreview(session));
                    return row;
                })
                .toList();
    }

    @Override
    public List<Message> loadMessages(String sessionId, int offset, int limit) {
        List<Message> all = loadMessages(sessionId, DEFAULT_MESSAGE_LIMIT);
        int from = Math.max(0, offset);
        if (from >= all.size()) {
            return List.of();
        }
        int to = limit > 0 ? Math.min(all.size(), from + limit) : all.size();
        return new ArrayList<>(all.subList(from, to));
    }

    private String firstUserPreview(Session session) {
        if (session.getMessages() == null) {
            return "";
        }
        return session.getMessages().stream()
                .filter(message -> "user".equals(message.getRole()))
                .map(Message::getContent)
                .filter(content -> content != null && !content.isBlank())
                .findFirst()
                .map(content -> content.length() > 80 ? content.substring(0, 80) : content)
                .orElse("");
    }

    private Session copySession(Session source) {
        return Session.builder()
                .sessionId(source.getSessionId())
                .name(source.getName())
                .model(source.getModel())
                .messages(source.getMessages() != null
                        ? new ArrayList<>(source.getMessages()) : new ArrayList<>())
                .build();
    }
}
