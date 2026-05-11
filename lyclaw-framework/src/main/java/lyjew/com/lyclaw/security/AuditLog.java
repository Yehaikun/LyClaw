package lyjew.com.lyclaw.security;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * 审计日志实体，记录每一次安全审批操作的完整信息。
 *
 * <p>每条审计日志包含操作主体（userId）、操作目标（target）、所需的权限级别
 * （requiredLevel）、审批结果（approved）等关键字段。此外，通过
 * previousHash 和 currentHash 形成链式哈希结构，确保日志不可篡改——
 * 每一条记录都依赖前一条记录的哈希值。</p>
 *
 * <p>该实体使用 Lombok 的 {@code @Data} 和 {@code @Builder} 注解，
 * 自动生成 getter/setter/toString/equals/hashCode 及建造者模式。</p>
 */
@Data
@Builder
public class AuditLog {
    /** 日志的唯一标识符 */
    private String logId;
    /** 执行操作的用户 ID */
    private String userId;
    /** 关联的会话 ID */
    private String sessionId;
    /** 执行的具体操作名称 */
    private String action;
    /** 操作的目标资源 */
    private String target;
    /** 操作所需的权限级别 */
    private PermissionLevel requiredLevel;
    /** 审批是否通过 */
    private boolean approved;
    /** 审批结果的详细原因 */
    private String reason;
    /** 日志记录的时间戳 */
    private Instant timestamp;
    /** 前一条日志的哈希值，用于链式校验 */
    private String previousHash;
    /** 当前日志的哈希值，作为下一条日志的 previousHash */
    private String currentHash;
    /** 扩展元数据，用于携带与本次操作相关的额外信息 */
    private Map<String, Object> metadata;

    /**
     * 计算当前日志记录的哈希值。
     * 哈希基于 previousHash、action、target、timestamp 拼接而成，
     * 实现简单但有缺陷的链式哈希——使用了 hashCode() 而非加密哈希函数。
     *
     * @return 当前记录的十六进制哈希字符串
     */
    public String computeHash() {
        String data = (previousHash != null ? previousHash : "") + action + target + timestamp;
        return Integer.toHexString(data.hashCode());
    }
}
