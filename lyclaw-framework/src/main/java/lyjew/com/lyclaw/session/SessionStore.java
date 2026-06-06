package lyjew.com.lyclaw.session;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;

/**
 * 框架级会话存储 SPI。
 *
 * <p>应用可以通过提供自己的 {@code SessionStore} Bean，将默认内存实现替换为
 * Redis、JDBC、文件或其他持久化后端。</p>
 */
public interface SessionStore {

    Session createSession(String agentId, String model);

    Session getOrCreate(String sessionId, String agentId, String model);

    Optional<Session> getSession(String sessionId);

    void save(Session session);

    void deleteSession(String sessionId);

    List<Message> loadMessages(String sessionId, int limit);

    void saveMessages(String sessionId, List<Message> messages);

    default List<Map<String, Object>> listSessions(String agentId) {
        return List.of();
    }

    default List<Message> loadMessages(String sessionId, int offset, int limit) {
        List<Message> messages = loadMessages(sessionId, limit);
        if (offset <= 0) {
            return messages;
        }
        if (offset >= messages.size()) {
            return List.of();
        }
        return messages.subList(offset, messages.size());
    }

    default boolean renameSession(String sessionId, String name) {
        Optional<Session> session = getSession(sessionId);
        if (session.isEmpty()) {
            return false;
        }
        Session updated = session.get();
        updated.setName(name);
        save(updated);
        return true;
    }
}
