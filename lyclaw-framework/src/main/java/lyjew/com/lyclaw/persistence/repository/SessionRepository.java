package lyjew.com.lyclaw.persistence.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lyjew.com.lyclaw.config.StorageProperties;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.persistence.jsonl.JsonlReader;
import lyjew.com.lyclaw.persistence.jsonl.JsonlWriter;
import lyjew.com.lyclaw.persistence.sqlite.SqliteConnectionManager;

/**
 * Session持久化——协调JSONL（消息数据）和SQLite（元数据摘要）。
 *
 * 设计决策：JSONL是消息的真实来源（source of truth），SQLite存储元数据便于快速列表/筛选。
 * 写入顺序：先append JSONL（确保消息不丢失），再UPDATE SQLite（元数据可修复）。
 * 读取时SQLite提供元数据索引，JSONL提供完整消息内容。
 */
public class SessionRepository {

    private final SqliteConnectionManager cm;
    private final JsonlWriter jsonlWriter;
    private final JsonlReader jsonlReader;
    private final StorageProperties storageProperties;

    public SessionRepository(SqliteConnectionManager cm, JsonlWriter jsonlWriter,
                             JsonlReader jsonlReader, StorageProperties storageProperties) {
        this.cm = cm; this.jsonlWriter = jsonlWriter; this.jsonlReader = jsonlReader;
        this.storageProperties = storageProperties;
    }

    /**
     * 创建新会话——INSERT SQLite + 创建JSONL文件 + 写入session_created首行。
     * 两步操作不在同一事务中（文件系统无法参与JDBC事务），
     * 但JSONL写入在前可保证消息数据不丢失。
     */
    public void create(Session session) {
        long now = System.currentTimeMillis();
        String filePath = session.getFilePath();

        // 1. 写入JSONL首行（session_created事件）——消息数据优先
        Map<String, Object> firstLine = new LinkedHashMap<>();
        firstLine.put("type", "session_created");
        firstLine.put("sessionId", session.getSessionId());
        firstLine.put("agentId", session.getAgentId());
        firstLine.put("parentSessionId", session.getParentSessionId());
        firstLine.put("parentAgentId", session.getParentAgentId());
        firstLine.put("timestamp", now);
        jsonlWriter.appendLine(filePath, firstLine);

        // 2. 插入SQLite元数据——用于快速列表查询
        String sql = """
            INSERT INTO sessions (session_id, agent_id, parent_session_id, parent_agent_id,
                created_at, updated_at, message_count, tool_call_count, total_tokens,
                compaction_count, file_path, first_msg_preview)
            VALUES (?,?,?,?,?,?,0,0,0,0,?,'')
            """;
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, session.getSessionId());
            ps.setString(2, session.getAgentId());
            ps.setString(3, session.getParentSessionId());
            ps.setString(4, session.getParentAgentId());
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.setString(7, filePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("创建Session SQLite记录失败", e);
        }
    }

    /**
     * 追加消息行到JSONL + 更新SQLite摘要。
     * 先写JSONL确保消息不丢，再更新SQLite统计字段（messageCount、toolCallCount、totalTokens）。
     */
    public void appendMessage(Session session, Map<String, Object> messageFields) {
        String filePath = session.getFilePath();
        // 1. 追加JSONL行——消息数据优先
        jsonlWriter.appendLine(filePath, messageFields);

        // 2. 更新SQLite摘要统计
        String sql = """
            UPDATE sessions SET updated_at = ?, message_count = ?,
                tool_call_count = tool_call_count + ?,
                total_tokens = total_tokens + ?
            WHERE session_id = ?
            """;
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            ps.setLong(1, now);
            ps.setInt(2, session.getMessageIndex());
            int toolCount = "tool_result".equals(messageFields.get("type")) ? 1 : 0;
            ps.setInt(3, toolCount);
            int tokens = extractTokens(messageFields);
            ps.setInt(4, tokens);
            ps.setString(5, session.getSessionId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新Session摘要失败", e);
        }
    }

    /**
     * 追加compaction事件行到JSONL——记录压缩操作元数据。
     * 不更新SQLite（compaction_count在外部单独维护）。
     */
    public void appendCompaction(String filePath, int messagesCompacted, int summaryTokens, double qualityScore) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "compaction");
        line.put("messagesCompacted", messagesCompacted);
        line.put("summaryTokens", summaryTokens);
        line.put("qualityScore", qualityScore);
        line.put("timestamp", System.currentTimeMillis());
        jsonlWriter.appendLine(filePath, line);
    }

    /** 读取JSONL首行（session_created事件），用于会话恢复时获取base信息 */
    public Map<String, Object> readFirstLine(String filePath) {
        return jsonlReader.readFirstLine(filePath);
    }

    /** 按sessionId查询会话元数据（不含消息内容，消息通过readMessages获取） */
    public List<Map<String, Object>> findBySessionId(String sessionId) {
        String sql = "SELECT session_id, agent_id, parent_session_id, parent_agent_id, " +
                "created_at, updated_at, first_msg_preview, message_count, " +
                "tool_call_count, total_tokens, compaction_count, file_path " +
                "FROM sessions WHERE session_id = ?";
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rowToMap(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询会话失败: " + sessionId, e);
        }
        return result;
    }

    /** 按agentId查询该Agent下所有会话，按更新时间降序（最新会话在前） */
    public List<Map<String, Object>> findByAgentId(String agentId) {
        String sql = "SELECT session_id, agent_id, parent_session_id, parent_agent_id, " +
                "created_at, updated_at, first_msg_preview, message_count, " +
                "tool_call_count, total_tokens, compaction_count, file_path " +
                "FROM sessions WHERE agent_id = ? ORDER BY updated_at DESC";
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rowToMap(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询会话列表失败", e);
        }
        return result;
    }

    /** 查询某父会话的所有子会话（按创建时间升序） */
    public List<Map<String, Object>> findByParentSessionId(String parentSessionId) {
        String sql = "SELECT session_id, agent_id, created_at, updated_at, message_count, " +
                "tool_call_count, file_path " +
                "FROM sessions WHERE parent_session_id = ? ORDER BY created_at";
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, parentSessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rowToMap(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询子会话列表失败", e);
        }
        return result;
    }

    /**
     * 更新首条用户消息预览——显示在会话列表中。
     * 预览长度由{@link StorageProperties.SessionProperties#previewMaxLength}控制，超出部分截断。
     */
    public void updateFirstMsgPreview(String sessionId, String preview) {
        String sql = "UPDATE sessions SET first_msg_preview = ? WHERE session_id = ?";
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int maxLen = storageProperties.getSession().getPreviewMaxLength();
            ps.setString(1, preview != null && preview.length() > maxLen
                    ? preview.substring(0, maxLen) : preview);
            ps.setString(2, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新首条消息预览失败", e);
        }
    }

    /** 分页读取JSONL消息行，offset=-1取最新limit条 */
    public List<Map<String, Object>> readMessages(String filePath, int offset, int limit) {
        return jsonlReader.readRange(filePath, offset, limit);
    }

    /** 删除会话——先删除JSONL文件，再删除SQLite行（顺序不重要，两侧独立清理） */
    public void delete(String sessionId, String filePath) {
        try { java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(filePath)); }
        catch (java.io.IOException e) { /* 文件不存在或无法删除不阻断SQLite行删除 */ }
        String sql = "DELETE FROM sessions WHERE session_id = ?";
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("删除Session失败", e);
        }
    }

    /** 从消息字段Map中提取token用量，用于更新sessions表的total_tokens统计 */
    private int extractTokens(Map<String, Object> fields) {
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) fields.get("usage");
        if (usage != null && usage.get("totalTokens") instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    /** 将ResultSet当前行转换为Map，key为列名，value为列值 */
    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> map = new LinkedHashMap<>();
        var meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            map.put(meta.getColumnName(i), rs.getObject(i));
        }
        return map;
    }
}
