package lyjew.com.lyclaw.security;

import java.time.Instant;

public class ApprovalResult {

    private final boolean approved;
    private final String reason;
    private final String approvedBy;
    private final Instant approvedAt;
    private final SandboxLevel sandboxLevel;

    public ApprovalResult(boolean approved, String reason, String approvedBy,
                          Instant approvedAt, SandboxLevel sandboxLevel) {
        this.approved = approved;
        this.reason = reason;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
        this.sandboxLevel = sandboxLevel;
    }

    public static ApprovalResult granted(SandboxLevel level) {
        return new ApprovalResult(true, "Approved", "SYSTEM", Instant.now(), level);
    }

    public static ApprovalResult denied(String reason) {
        return new ApprovalResult(false, reason, "SYSTEM", Instant.now(), null);
    }

    public boolean isApproved() { return approved; }

    public String getReason() { return reason; }

    public String getApprovedBy() { return approvedBy; }

    public Instant getApprovedAt() { return approvedAt; }

    public SandboxLevel getSandboxLevel() { return sandboxLevel; }
}
