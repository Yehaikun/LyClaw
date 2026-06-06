package lyjew.com.lyclaw.session;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.SessionQuery;

/**
 * 框架级会话存储 SPI（旧版，已弃用）。
 *
 * <p>建议使用 {@link SessionService} 门面代替直接操作 SessionStore。
 * SessionService 组合了 SessionStore + {@link MessageStore} + {@link VariableStore}，
 * 并提供写策略和上下文裁剪能力。</p>
 *
 * <p>应用如需要自定义存储实现，请实现 {@link MessageStore}、{@link VariableStore}
 * 和 {@link SessionStore}，框架的 {@code DefaultSessionService} 会自动组合它们。</p>
 *
 * @deprecated 请使用 {@link SessionService} 代替。SessionStore 仅作为底层 SPI 保留。
 */
@Deprecated
public interface SessionStore {

    Session createSession(String agentId, String model);

    Session getOrCreate(String sessionId, String agentId, String model);

    Optional<Session> getSession(String sessionId);

    void save(Session session);

    void deleteSession(String sessionId);

    List<Message> loadMessages(String sessionId, int limit);

    void saveMessages(String sessionId, List<Message> messages);

    /**
     * 按查询条件列出会话。
     *
     * @param query 查询条件（agentId、status、分页等）
     * @return 匹配的会话列表
     */
    default List<Session> list(SessionQuery query) {
        Set<String> added = new HashSet<>();
        return listSessions(query != null ? query.getAgentId() : null).stream()
                .filter(m -> {
                    String sid = m.get("sessionId") instanceof String s ? s : null;
                    return sid != null && added.add(sid);
                })
                .map(m -> {
                    String sid = m.get("sessionId") instanceof String s ? s : "";
                    Session s = Session.builder().sessionId(sid).build();
                    if (m.get("name") instanceof String n) s.setName(n);
                    if (m.get("agentId") instanceof String a) s.setAgentId(a);
                    if (m.get("model") instanceof String mo) s.setModel(mo);
                    return s;
                })
                .toList();
    }

    /**
     * 按查询条件统计会话数。
     */
    default int count(SessionQuery query) {
        return list(query).size();
    }

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
