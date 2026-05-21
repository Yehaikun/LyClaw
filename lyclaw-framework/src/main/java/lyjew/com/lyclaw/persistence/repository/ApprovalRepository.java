package lyjew.com.lyclaw.persistence.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lyjew.com.lyclaw.persistence.sqlite.SqliteConnectionManager;

/**
 * 审批持久化——工具调用前的用户确认记录。
 *
 * 每当delegate_to_agent或敏感工具需要审批时，创建一条pending记录。
 * 前端展示审批列表，用户同意/拒绝后调用resolve更新状态。
 * 超时未审批的记录由ContextPruningScheduler清理（Phase 3）。
 */
public class ApprovalRepository {

    private final SqliteConnectionManager cm;

    public ApprovalRepository(SqliteConnectionManager cm) { this.cm = cm; }

    /**
     * 插入一条待审批记录。
     * @param expiresAt 审批过期时间戳（毫秒），超时后自动视为拒绝
     */
    public void insert(String approvalId, String sessionId, String agentId,
                       String toolName, String toolCallId, String arguments,
                       long expiresAt) {
        String sql = """
            INSERT INTO approvals (approval_id, session_id, agent_id, tool_name,
                tool_call_id, arguments, status, requested_at, expires_at)
            VALUES (?,?,?,?,?,?,'pending',?,?)
            """;
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            ps.setString(1, approvalId);
            ps.setString(2, sessionId);
            ps.setString(3, agentId);
            ps.setString(4, toolName);
            ps.setString(5, toolCallId);
            ps.setString(6, arguments);
            ps.setLong(7, now);
            ps.setLong(8, expiresAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("插入审批失败", e);
        }
    }

    /**
     * 审批决议——将状态从pending更新为approved或rejected。
     * @param status 新状态："approved" 或 "rejected"
     * @param resolvedBy 审批人标识（用户ID或"system"用于自动过期）
     */
    public void resolve(String approvalId, String status, String resolvedBy) {
        String sql = "UPDATE approvals SET status=?, resolved_at=?, resolved_by=? WHERE approval_id=?";
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, resolvedBy);
            ps.setString(4, approvalId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新审批状态失败", e);
        }
    }

    /** 查询某会话下所有待审批记录，按请求时间升序（先请求的先展示） */
    public List<Map<String, Object>> findPendingBySession(String sessionId) {
        String sql = "SELECT * FROM approvals WHERE session_id=? AND status='pending' ORDER BY requested_at";
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    var meta = rs.getMetaData();
                    for (int i = 1; i <= meta.getColumnCount(); i++)
                        row.put(meta.getColumnName(i), rs.getObject(i));
                    result.add(row);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询待审批列表失败", e);
        }
        return result;
    }
}
