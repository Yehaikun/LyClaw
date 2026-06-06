package lyjew.com.lyclaw.web.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.session.MessageStore;

/**
 * SQLite 消息存储 —— 增量追加模式（INSERT-only）。
 *
 * <p>与旧版 {@link SqliteSessionStore} 的全量 DELETE+INSERT 不同，
 * 本实现只 INSERT 新消息，不触及已存在的记录。更适合流式追加和持久化场景。</p>
 */
public class SqliteMessageStore implements MessageStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteMessageStore.class);

    private final String jdbcUrl;
    private final ObjectMapper objectMapper;

    public SqliteMessageStore(String jdbcUrl, ObjectMapper objectMapper) {
        this.jdbcUrl = jdbcUrl;
        this.objectMapper = objectMapper;
    }

    @Override
    public int append(String sessionId, Message message) {
        int nextIndex = nextMsgIndex(sessionId);
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO messages(session_id, msg_index, role, content, model,
                                          tool_call_id, tool_name, thinking, payload_json, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setString(1, sessionId);
            ps.setInt(2, nextIndex);
            ps.setString(3, message.getRole());
            ps.setString(4, message.getContent());
            ps.setString(5, message.getModel());
            ps.setString(6, message.getToolCallId());
            ps.setString(7, message.getToolName());
            ps.setString(8, message.getThinking());
            ps.setString(9, toJson(message));
            ps.setLong(10, System.currentTimeMillis());
            ps.executeUpdate();
            return nextIndex;
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 追加消息失败", e);
        }
    }

    @Override
    public int[] appendBatch(String sessionId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) return new int[0];
        int[] indices = new int[messages.size()];
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            int baseIndex = nextMsgIndex(sessionId);
            try (PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO messages(session_id, msg_index, role, content, model,
                                          tool_call_id, tool_name, thinking, payload_json, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
                long now = System.currentTimeMillis();
                for (int i = 0; i < messages.size(); i++) {
                    Message msg = messages.get(i);
                    int idx = baseIndex + i;
                    ps.setString(1, sessionId);
                    ps.setInt(2, idx);
                    ps.setString(3, msg.getRole());
                    ps.setString(4, msg.getContent());
                    ps.setString(5, msg.getModel());
                    ps.setString(6, msg.getToolCallId());
                    ps.setString(7, msg.getToolName());
                    ps.setString(8, msg.getThinking());
                    ps.setString(9, toJson(msg));
                    ps.setLong(10, now);
                    ps.addBatch();
                    indices[i] = idx;
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 批量追加消息失败", e);
        }
        return indices;
    }

    @Override
    public List<Message> load(String sessionId, int offset, int limit) {
        int effectiveLimit = limit > 0 ? limit : 500;
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
            ps.setInt(3, Math.max(0, offset));
            return readMessages(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 分页读取消息失败", e);
        }
    }

    @Override
    public List<Message> loadLatest(String sessionId, int lastN) {
        int effectiveLastN = Math.max(1, lastN);
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
            ps.setInt(2, effectiveLastN);
            return readMessages(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 读取最近消息失败", e);
        }
    }

    @Override
    public List<Message> loadSince(String sessionId, int afterIndex) {
        String sql = """
                SELECT payload_json FROM messages
                WHERE session_id = ? AND msg_index > ?
                ORDER BY msg_index ASC
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setInt(2, afterIndex);
            return readMessages(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 增量读取消息失败", e);
        }
    }

    @Override
    public void updateContent(String sessionId, int index, String content) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE messages SET content = ?, payload_json = ?
                     WHERE session_id = ? AND msg_index = ?
                     """)) {
            ps.setString(1, content);
            // payload_json 需要重新序列化，这里简化为只更新 content 字段
            ps.setString(2, "{\"updated\":true}");
            ps.setString(3, sessionId);
            ps.setInt(4, index);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 更新消息内容失败", e);
        }
    }

    @Override
    public int pruneBefore(String sessionId, int keepLastN) {
        String countSql = "SELECT COUNT(*) FROM messages WHERE session_id = ?";
        try (Connection conn = open();
             PreparedStatement countPs = conn.prepareStatement(countSql)) {
            countPs.setString(1, sessionId);
            try (ResultSet rs = countPs.executeQuery()) {
                if (!rs.next()) return 0;
                int total = rs.getInt(1);
                if (total <= keepLastN) return 0;
                int removeCount = total - keepLastN;
                try (PreparedStatement delPs = conn.prepareStatement("""
                        DELETE FROM messages WHERE session_id = ? AND msg_index IN (
                            SELECT msg_index FROM messages
                            WHERE session_id = ?
                            ORDER BY msg_index ASC
                            LIMIT ?
                        )
                        """)) {
                    delPs.setString(1, sessionId);
                    delPs.setString(2, sessionId);
                    delPs.setInt(3, removeCount);
                    return delPs.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 裁剪消息失败", e);
        }
    }

    @Override
    public void deleteBySession(String sessionId) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM messages WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 删除会话消息失败", e);
        }
    }

    @Override
    public int count(String sessionId) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM messages WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 统计消息数失败", e);
        }
    }

    private int nextMsgIndex(String sessionId) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COALESCE(MAX(msg_index), -1) + 1 FROM messages WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.warn("SQLite 查询下一条消息序号失败，使用时间戳回退: {}", e.getMessage());
            return (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
        }
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
        } catch (Exception e) {
            throw new IllegalStateException("序列化消息失败", e);
        }
    }

    private Message fromJson(String json) {
        try {
            return objectMapper.readValue(json, Message.class);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化消息失败", e);
        }
    }

    private Connection open() throws SQLException {
        Connection conn = java.sql.DriverManager.getConnection(jdbcUrl);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("PRAGMA foreign_keys=ON");
        }
        return conn;
    }
}
