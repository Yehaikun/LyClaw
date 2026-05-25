package lyjew.com.lyclaw.web.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import lyjew.com.lyclaw.model.Session;

/**
 * 纯内存会话管理器——Session 仅存于 ConcurrentHashMap，不涉及任何持久化。
 *
 * <p>提供 createSession / getSession / deleteSession 等基本操作，
 * 仅用于在 lyclaw-web 中测试 ReAct 循环，重启即丢失。</p>
 */
public class SessionManager {

    private final ConcurrentHashMap<String, Session> activeSessions = new ConcurrentHashMap<>();

    public Session createSession(String agentId, String model) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        Session session = Session.builder()
                .sessionId(sessionId)
                .name("Chat")
                .model(model)
                .messages(new ArrayList<>())
                .build();
        activeSessions.put(sessionId, session);
        return session;
    }

    public Session getSession(String sessionId) {
        return activeSessions.get(sessionId);
    }

    public void deleteSession(String sessionId) {
        activeSessions.remove(sessionId);
    }

    public Map<String, Session> getActiveSessions() {
        return Collections.unmodifiableMap(activeSessions);
    }
}
