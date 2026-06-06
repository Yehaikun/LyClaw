package lyjew.com.lyclaw.web.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 SQLite 文件的会话存储实现。
 *
 * <p>该类是 Web 部署层对框架 {@link SessionStore} SPI 的默认实现。框架只依赖
 * SPI，Web 层负责选择 SQLite 作为具体持久化介质。</p>
 */
public class SqliteSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteSessionStore.class);
    private static final int DEFAULT_LIMIT = 500;

    private final String jdbcUrl;
    private final ObjectMapper objectMapper;

    public SqliteSessionStore(Path databasePath, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        prepareParentDir(databasePath);
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
        initSchema();
        log.info("SQLite 会话存储已初始化: {}", databasePath.toAbsolutePath());
    }

    @Override
    public synchronized Session createSession(String agentId, String model) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        long now = now();
        String name = "Chat";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO sessions(session_id, agent_id, name, model, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setString(1, sessionId);
            ps.setString(2, normalizeAgentId(agentId));
            ps.setString(3, name);
            ps.setString(4, model);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
            return Session.builder()
                    .sessionId(sessionId)
                    .name(name)
                    .model(model)
                    .messages(new ArrayList<>())
                    .build();
        } catch (SQLException e) {
            throw new IllegalStateException("创建 SQLite 会话失败", e);
        }
    }

    @Override
    public synchronized Session getOrCreate(String sessionId, String agentId, String model) {
        if (sessionId == null || sessionId.isBlank()) {
            return createSession(agentId, model);
        }
        Optional<Session> existing = getSession(sessionId);
        if (existing.isPresent()) {
            return existing.get();
        }
        long now = now();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO sessions(session_id, agent_id, name, model, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setString(1, sessionId);
            ps.setString(2, normalizeAgentId(agentId));
            ps.setString(3, "Chat");
            ps.setString(4, model);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
            return Session.builder()
                    .sessionId(sessionId)
                    .name("Chat")
                    .model(model)
                    .messages(new ArrayList<>())
                    .build();
        } catch (SQLException e) {
            throw new IllegalStateException("获取或创建 SQLite 会话失败", e);
        }
    }

    @Override
    public synchronized Optional<Session> getSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT session_id, name, model FROM sessions WHERE session_id = ?
                     """)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(Session.builder()
                        .sessionId(rs.getString("session_id"))
                        .name(rs.getString("name"))
                        .model(rs.getString("model"))
                        .messages(loadMessages(sessionId, DEFAULT_LIMIT))
                        .build());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 SQLite 会话失败", e);
        }
    }

    @Override
    public synchronized void save(Session session) {
        if (session == null || session.getSessionId() == null || session.getSessionId().isBlank()) {
            return;
        }
        getOrCreate(session.getSessionId(), session.getName(), session.getModel());
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE sessions SET name = ?, model = ?, updated_at = ? WHERE session_id = ?
                     """)) {
            ps.setString(1, session.getName());
            ps.setString(2, session.getModel());
            ps.setLong(3, now());
            ps.setString(4, session.getSessionId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存 SQLite 会话失败", e);
        }
        saveMessages(session.getSessionId(), session.getMessages());
    }

    @Override
    public synchronized void deleteSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try (PreparedStatement delMessages = conn.prepareStatement("DELETE FROM messages WHERE session_id = ?");
                 PreparedStatement delSession = conn.prepareStatement("DELETE FROM sessions WHERE session_id = ?")) {
                delMessages.setString(1, sessionId);
                delMessages.executeUpdate();
                delSession.setString(1, sessionId);
                delSession.executeUpdate();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("删除 SQLite 会话失败", e);
        }
    }

    @Override
    public synchronized List<Message> loadMessages(String sessionId, int limit) {
        int effectiveLimit = limit > 0 ? limit : DEFAULT_LIMIT;
        String sql = """
                SELECT payload_json FROM (
                    SELECT msg_index, payload_json FROM messages
                    WHERE session_id = ?
                    ORDER BY msg_index DESC
                    LIMIT ?
                ) ORDER BY msg_index ASC
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setInt(2, effectiveLimit);
            return readMessages(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("读取 SQLite 消息失败", e);
        }
    }

    @Override
    public synchronized List<Message> loadMessages(String sessionId, int offset, int limit) {
        int effectiveLimit = limit > 0 ? limit : DEFAULT_LIMIT;
        String sql = """
                SELECT payload_json FROM messages
                WHERE session_id = ?
                ORDER BY msg_index ASC
                LIMIT ? OFFSET ?
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setInt(2, effectiveLimit);
            ps.setInt(3, Math.max(offset, 0));
            return readMessages(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("分页读取 SQLite 消息失败", e);
        }
    }

    @Override
    public synchronized void saveMessages(String sessionId, List<Message> messages) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        getOrCreate(sessionId, "chat", null);
        List<Message> copy = messages != null ? new ArrayList<>(messages) : List.of();
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try (PreparedStatement delete = conn.prepareStatement("DELETE FROM messages WHERE session_id = ?");
                 PreparedStatement insert = conn.prepareStatement("""
                         INSERT INTO messages(session_id, msg_index, role, content, model,
                                              tool_call_id, tool_name, thinking, payload_json, created_at)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                         """);
                 PreparedStatement touch = conn.prepareStatement("""
                         UPDATE sessions
                         SET updated_at = ?,
                             name = CASE WHEN name IS NULL OR name = '' OR name = 'Chat' THEN ? ELSE name END
                         WHERE session_id = ?
                         """)) {
                delete.setString(1, sessionId);
                delete.executeUpdate();
                long now = now();
                for (int i = 0; i < copy.size(); i++) {
                    Message message = copy.get(i);
                    insert.setString(1, sessionId);
                    insert.setInt(2, i);
                    insert.setString(3, message.getRole());
                    insert.setString(4, message.getContent());
                    insert.setString(5, message.getModel());
                    insert.setString(6, message.getToolCallId());
                    insert.setString(7, message.getToolName());
                    insert.setString(8, message.getThinking());
                    insert.setString(9, toJson(message));
                    insert.setLong(10, now);
                    insert.addBatch();
                }
                insert.executeBatch();
                touch.setLong(1, now);
                touch.setString(2, defaultName(copy));
                touch.setString(3, sessionId);
                touch.executeUpdate();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存 SQLite 消息失败", e);
        }
    }

    @Override
    public synchronized List<Map<String, Object>> listSessions(String agentId) {
        String sql = """
                SELECT s.session_id, s.agent_id, s.name, s.model, s.created_at, s.updated_at,
                       COUNT(m.id) AS message_count,
                       SUM(CASE WHEN m.role = 'tool' THEN 1 ELSE 0 END) AS tool_call_count,
                       MIN(CASE WHEN m.role = 'user' THEN m.content ELSE NULL END) AS first_msg_preview
                FROM sessions s
                LEFT JOIN messages m ON s.session_id = m.session_id
                WHERE (? IS NULL OR ? = '' OR s.agent_id = ?)
                GROUP BY s.session_id
                ORDER BY s.updated_at DESC
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            String normalized = agentId == null ? "" : agentId;
            ps.setString(1, normalized);
            ps.setString(2, normalized);
            ps.setString(3, normalized);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> result = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sessionId", rs.getString("session_id"));
                    row.put("session_id", rs.getString("session_id"));
                    row.put("agentId", rs.getString("agent_id"));
                    row.put("agent_id", rs.getString("agent_id"));
                    row.put("name", rs.getString("name"));
                    row.put("model", rs.getString("model"));
                    row.put("createdAt", Instant.ofEpochMilli(rs.getLong("created_at")).toString());
                    row.put("updatedAt", Instant.ofEpochMilli(rs.getLong("updated_at")).toString());
                    row.put("created_at", rs.getLong("created_at"));
                    row.put("updated_at", rs.getLong("updated_at"));
                    row.put("messageCount", rs.getInt("message_count"));
                    row.put("message_count", rs.getInt("message_count"));
                    row.put("toolCallCount", rs.getInt("tool_call_count"));
                    row.put("tool_call_count", rs.getInt("tool_call_count"));
                    row.put("totalTokens", 0);
                    row.put("total_tokens", 0);
                    row.put("compactionCount", 0);
                    row.put("compaction_count", 0);
                    row.put("firstMsgPreview", preview(rs.getString("first_msg_preview")));
                    row.put("first_msg_preview", preview(rs.getString("first_msg_preview")));
                    result.add(row);
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("列出 SQLite 会话失败", e);
        }
    }

    @Override
    public synchronized boolean renameSession(String sessionId, String name) {
        if (sessionId == null || sessionId.isBlank() || name == null || name.isBlank()) {
            return false;
        }
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE sessions SET name = ?, updated_at = ? WHERE session_id = ?
                     """)) {
            ps.setString(1, name.trim());
            ps.setLong(2, now());
            ps.setString(3, sessionId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("重命名 SQLite 会话失败", e);
        }
    }

    private void initSchema() {
        try (Connection conn = open();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("PRAGMA journal_mode=WAL");
            stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS sessions (
                        session_id TEXT PRIMARY KEY,
                        agent_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        model TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        session_id TEXT NOT NULL,
                        msg_index INTEGER NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT,
                        model TEXT,
                        tool_call_id TEXT,
                        tool_name TEXT,
                        thinking TEXT,
                        payload_json TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(session_id) REFERENCES sessions(session_id) ON DELETE CASCADE
                    )
                    """);
            stmt.executeUpdate("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_session_index
                    ON messages(session_id, msg_index)
                    """);
            stmt.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_sessions_agent_updated
                    ON sessions(agent_id, updated_at DESC)
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("初始化 SQLite schema 失败", e);
        }
    }

    private Connection open() throws SQLException {
        Connection conn = DriverManager.getConnection(jdbcUrl);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("PRAGMA foreign_keys=ON");
        }
        return conn;
    }

    private List<Message> readMessages(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<Message> messages = new ArrayList<>();
            while (rs.next()) {
                messages.add(fromJson(rs.getString("payload_json")));
            }
            return messages;
        }
    }

    private String toJson(Message message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (IOException e) {
            throw new IllegalStateException("序列化消息失败", e);
        }
    }

    private Message fromJson(String json) {
        try {
            return objectMapper.readValue(json, Message.class);
        } catch (IOException e) {
            throw new IllegalStateException("反序列化消息失败", e);
        }
    }

    private void prepareParentDir(Path databasePath) {
        Path parent = databasePath.toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new IllegalStateException("创建 SQLite 数据目录失败: " + parent, e);
        }
    }

    private String normalizeAgentId(String agentId) {
        return agentId != null && !agentId.isBlank() ? agentId : "chat";
    }

    private long now() {
        return System.currentTimeMillis();
    }

    private String defaultName(List<Message> messages) {
        return messages.stream()
                .filter(message -> "user".equals(message.getRole()))
                .map(Message::getContent)
                .filter(content -> content != null && !content.isBlank())
                .findFirst()
                .map(this::preview)
                .orElse("Chat");
    }

    private String preview(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String trimmed = content.trim().replaceAll("\\s+", " ");
        return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
    }
}
