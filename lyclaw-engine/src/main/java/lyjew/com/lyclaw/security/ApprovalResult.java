package lyjew.com.lyclaw.security;

import java.time.Instant;

/**
 * 审批结果值对象 —— SecurityManager.approve() 的返回值。
 *
 * <p>审批结果包含是否通过、拒绝原因、审批人信息、审批时间和执行沙箱级别。
 * 通过静态工厂方法 {@link #granted(SandboxLevel)} 和 {@link #denied(String)}
 * 快速构造常见结果。</p>
 *
 * <p><b>设计动机</b>：审批结果是跨模块传递的数据对象，用值对象确保不可变。
 * 静态工厂方法比构造器更语义化：ApprovalResult.granted(NONE) 一看就知道是通过审批。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see SecurityManager
 */
public class ApprovalResult {

    /** 是否通过审批 */
    private final boolean approved;

    /** 审批理由（通过时）或拒绝原因（拒绝时）。拒绝时不应为 null */
    private final String reason;

    /** 审批人标识。系统自动审批时为 "SYSTEM" */
    private final String approvedBy;

    /** 审批时间 */
    private final Instant approvedAt;

    /** 审批通过的沙箱级别。拒绝时取值为 null */
    private final SandboxLevel sandboxLevel;

    /**
     * 构造一个完整的审批结果。
     *
     * @param approved     是否通过
     * @param reason       理由或原因
     * @param approvedBy   审批人
     * @param approvedAt   审批时间
     * @param sandboxLevel 沙箱级别（拒绝时为 null）
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
     * 快速创建"审批通过"结果。
     *
     * @param level 审批通过的沙箱级别
     * @return 通过的审批结果
     */
    public static ApprovalResult granted(SandboxLevel level) {
        return new ApprovalResult(true, "Approved", "SYSTEM",
                Instant.now(), level);
    }

    /**
     * 快速创建"审批拒绝"结果。
     *
     * @param reason 拒绝原因
     * @return 拒绝的审批结果
     */
    public static ApprovalResult denied(String reason) {
        return new ApprovalResult(false, reason, "SYSTEM",
                Instant.now(), null);
    }

    /** @return 是否通过审批 */
    public boolean isApproved() { return approved; }

    /** @return 审批理由或拒绝原因 */
    public String getReason() { return reason; }

    /** @return 审批人 */
    public String getApprovedBy() { return approvedBy; }

    /** @return 审批时间 */
    public Instant getApprovedAt() { return approvedAt; }

    /** @return 审批通过的沙箱级别（拒绝时为 null） */
    public SandboxLevel getSandboxLevel() { return sandboxLevel; }
}