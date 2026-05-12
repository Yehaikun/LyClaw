package lyjew.com.lyclaw.security;

import java.time.Instant;
import java.util.List;

/**
 * 审计日志接口，负责安全事件的记录、查询和导出。
 */
public interface AuditLogger {

    /** 记录一条审计日志。 */
    void log(String userId, String sessionId, String action, String target,
             PermissionLevel requiredLevel, boolean approved, String reason);

    /** @return 所有审计日志的不可变副本 */
    List<AuditLog> exportAll();

    /** 按用户查询审计日志。 */
    List<AuditLog> exportByUser(String userId);

    /** 按会话查询审计日志。 */
    List<AuditLog> exportBySession(String sessionId);

    /** 按时间范围查询审计日志。 */
    List<AuditLog> exportByTimeRange(Instant from, Instant to);

    /** @return 当前审计日志条目数 */
    int size();

    /** 清空所有审计日志。 */
    void clear();
}
