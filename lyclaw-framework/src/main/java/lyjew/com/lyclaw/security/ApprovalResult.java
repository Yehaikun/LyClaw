package lyjew.com.lyclaw.security;

import java.time.Instant;

/**
 * 审批结果实体，封装安全审批的最终结果。
 *
 * <p>当 {@link SecurityManager} 对某个操作进行安全审批时，返回该实体。
 * 它包含审批是否通过、拒绝原因、审批者身份、审批时间，以及在通过时分配的
 * 沙箱级别（{@link SandboxLevel}）。</p>
 *
 * <p>提供两个静态工厂方法简化使用：</p>
 * <ul>
 *   <li>{@link #granted(SandboxLevel)} - 创建已通过的审批结果</li>
 *   <li>{@link #denied(String)} - 创建已拒绝的审批结果</li>
 * </ul>
 */
public class ApprovalResult {

    /** 是否通过审批 */
    private final boolean approved;
    /** 审批结果的原因说明（通过时为批准理由，拒绝时为拒绝原因） */
    private final String reason;
    /** 审批者标识（SYSTEM 表示自动审批，也可以是用户名） */
    private final String approvedBy;
    /** 审批发生的时间点 */
    private final Instant approvedAt;
    /** 审批通过时分配的沙箱级别，拒绝时为 null */
    private final SandboxLevel sandboxLevel;

    /**
     * 构造审批结果。
     *
     * @param approved     是否批准
     * @param reason       原因说明
     * @param approvedBy   审批者标识
     * @param approvedAt   审批时间
     * @param sandboxLevel 沙箱级别
     */
    public ApprovalResult(boolean approved, String reason, String approvedBy,
                          Instant approvedAt, SandboxLevel sandboxLevel) {
        this.approved = approved;
        this.reason = reason;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
        this.sandboxLevel = sandboxLevel;
    }

    /**
     * 创建一个「已批准」的审批结果。
     *
     * @param level 分配的沙箱级别
     * @return 已批准的审批结果实例
     */
    public static ApprovalResult granted(SandboxLevel level) {
        return new ApprovalResult(true, "Approved", "SYSTEM", Instant.now(), level);
    }

    /**
     * 创建一个「已拒绝」的审批结果。
     *
     * @param reason 拒绝的具体原因
     * @return 已拒绝的审批结果实例（sandboxLevel 为 null）
     */
    public static ApprovalResult denied(String reason) {
        return new ApprovalResult(false, reason, "SYSTEM", Instant.now(), null);
    }

    /** @return 是否通过审批 */
    public boolean isApproved() { return approved; }

    /** @return 审批原因说明 */
    public String getReason() { return reason; }

    /** @return 审批者标识 */
    public String getApprovedBy() { return approvedBy; }

    /** @return 审批时间 */
    public Instant getApprovedAt() { return approvedAt; }

    /** @return 沙箱级别，拒绝时为 null */
    public SandboxLevel getSandboxLevel() { return sandboxLevel; }
}
