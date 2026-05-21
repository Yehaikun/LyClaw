package lyjew.com.lyclaw.persistence.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lyjew.com.lyclaw.persistence.sqlite.SqliteConnectionManager;

/**
 * Agent持久化操作——纯JDBC，无ORM。
 *
 * 设计决策：SQLite仅3张表，ORM的映射成本高于收益。
 * 直接使用JDBC PreparedStatement，字段变更只需改SQL字符串。
 * 所有方法抛出RuntimeException（非受检），调用方决定是否捕获。
 */
public class AgentRepository {

    private final SqliteConnectionManager cm;

    public AgentRepository(SqliteConnectionManager cm) { this.cm = cm; }

    /**
     * 插入新Agent记录。
     * @param agent 包含全部25列的字段Map，agent_id和agent_name为必填项
     */
    public void insert(Map<String, Object> agent) {
        String sql = """
            INSERT INTO agents (agent_id, agent_name, description, lifecycle, created_by,
                parent_agent_id, parent_session_id, model, provider, thinking_level,
                verbose_level, reasoning_level, fast_mode, sandbox_level, skills,
                allow_agents, max_spawn_depth, max_children, system_prompt, soul_prompt,
                identity_display_name, avatar_url, avatar_file_path, created_at, directory_path)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, (String) agent.get("agent_id"));
            ps.setString(2, (String) agent.get("agent_name"));
            ps.setString(3, (String) agent.getOrDefault("description", ""));
            ps.setString(4, (String) agent.getOrDefault("lifecycle", "permanent"));
            ps.setString(5, (String) agent.getOrDefault("created_by", "user"));
            ps.setString(6, (String) agent.get("parent_agent_id"));
            ps.setString(7, (String) agent.get("parent_session_id"));
            ps.setString(8, (String) agent.get("model"));
            ps.setString(9, (String) agent.get("provider"));
            ps.setString(10, (String) agent.getOrDefault("thinking_level", "medium"));
            ps.setString(11, (String) agent.getOrDefault("verbose_level", "low"));
            ps.setString(12, (String) agent.getOrDefault("reasoning_level", "medium"));
            ps.setInt(13, (Integer) agent.getOrDefault("fast_mode", 0));
            ps.setString(14, (String) agent.getOrDefault("sandbox_level", "PROCESS"));
            ps.setString(15, (String) agent.getOrDefault("skills", "[]"));
            ps.setString(16, (String) agent.getOrDefault("allow_agents", "[\"*\"]"));
            ps.setInt(17, (Integer) agent.getOrDefault("max_spawn_depth", 1));
            ps.setInt(18, (Integer) agent.getOrDefault("max_children", 5));
            ps.setString(19, (String) agent.getOrDefault("system_prompt", ""));
            ps.setString(20, (String) agent.getOrDefault("soul_prompt", ""));
            ps.setString(21, (String) agent.getOrDefault("identity_display_name", ""));
            ps.setString(22, (String) agent.getOrDefault("avatar_url", ""));
            ps.setString(23, (String) agent.getOrDefault("avatar_file_path", ""));
            ps.setLong(24, (Long) agent.get("created_at"));
            ps.setString(25, (String) agent.get("directory_path"));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("插入Agent失败: " + agent.get("agent_id"), e);
        }
    }

    /** 按agentId查询Agent全量信息，不存在返回null */
    public Map<String, Object> findById(String agentId) {
        String sql = "SELECT * FROM agents WHERE agent_id = ?";
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowToMap(rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询Agent失败: " + agentId, e);
        }
    }

    /**
     * 查询所有Agent摘要信息（列表页用，不含system_prompt/soul_prompt等大字段）。
     * 按created_at降序排列，最新创建的Agent排在最前。
     */
    public List<Map<String, Object>> findAllSummary() {
        String sql = """
            SELECT agent_id, agent_name, description, lifecycle, model, skills,
                   avatar_url, created_at
            FROM agents ORDER BY created_at DESC
            """;
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = cm.getConnection(); Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) result.add(rowToMap(rs));
        } catch (SQLException e) {
            throw new RuntimeException("查询Agent列表失败", e);
        }
        return result;
    }

    /** 按agentId更新指定字段。updates中只传需要修改的键值对，agent_id会被过滤忽略。 */
    public void update(String agentId, Map<String, Object> updates) {
        StringBuilder sb = new StringBuilder("UPDATE agents SET ");
        List<Object> values = new ArrayList<>();
        for (var entry : updates.entrySet()) {
            if (entry.getKey().equals("agent_id")) continue;
            sb.append(entry.getKey()).append(" = ?, ");
            values.add(entry.getValue());
        }
        sb.setLength(sb.length() - 2);  // 移除末尾", "
        sb.append(" WHERE agent_id = ?");
        values.add(agentId);
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sb.toString())) {
            for (int i = 0; i < values.size(); i++) {
                ps.setObject(i + 1, values.get(i));
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新Agent失败: " + agentId, e);
        }
    }

    /**
     * 删除Agent——级联删除由SQLite外键ON DELETE CASCADE自动处理sessions表。
     * JSONL文件需由调用方另行删除（文件系统操作不在此Repository职责范围）。
     */
    public void delete(String agentId) {
        String sql = "DELETE FROM agents WHERE agent_id = ?";
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("删除Agent失败: " + agentId, e);
        }
    }

    /** 查询某Agent的活跃子Agent数量（仅统计lifecycle='temporary'的临时Agent） */
    public int countChildren(String parentAgentId) {
        String sql = "SELECT COUNT(*) FROM agents WHERE parent_agent_id = ? AND lifecycle = 'temporary'";
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, parentAgentId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) {
            throw new RuntimeException("统计子Agent失败", e);
        }
    }

    /** 将ResultSet当前行转换为Map，key为列名，value为列值 */

    /**
     * 递归查询指定Agent的所有子孙临时Agent（lifecycle='temporary'）。
     * 使用SQLite WITH RECURSIVE CTE，按深度优先排序确保叶子节点在前。
     *
     * @param agentId 根Agent ID
     * @return 子孙临时Agent的ID列表（叶子在前，根本身在最后或不包含）
     */
    public List<String> findAllTemporaryDescendants(String agentId) {
        String sql = """
            WITH RECURSIVE descendants AS (
                SELECT agent_id, lifecycle, 1 AS depth FROM agents WHERE parent_agent_id = ?
                UNION ALL
                SELECT a.agent_id, a.lifecycle, d.depth + 1
                FROM agents a JOIN descendants d ON a.parent_agent_id = d.agent_id
            )
            SELECT agent_id FROM descendants WHERE lifecycle = 'temporary'
            ORDER BY depth DESC
            """;
        List<String> result = new ArrayList<>();
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString("agent_id"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询子孙临时Agent失败: " + agentId, e);
        }
        return result;
    }
    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> map = new LinkedHashMap<>();
        var meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            map.put(meta.getColumnName(i), rs.getObject(i));
        }
        return map;
    }
}
