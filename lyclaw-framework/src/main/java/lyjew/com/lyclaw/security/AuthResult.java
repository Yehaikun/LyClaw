package lyjew.com.lyclaw.security;

/**
 * 认证结果，包含用户身份和认证状态。
 */
public class AuthResult {

    private final String userId;
    private final boolean authenticated;
    private final String reason;

    public AuthResult(String userId, boolean authenticated, String reason) {
        this.userId = userId;
        this.authenticated = authenticated;
        this.reason = reason;
    }

    public static AuthResult success(String userId) {
        return new AuthResult(userId, true, "authenticated");
    }

    public static AuthResult failed(String reason) {
        return new AuthResult(null, false, reason);
    }

    public String getUserId() { return userId; }
    public boolean isAuthenticated() { return authenticated; }
    public String getReason() { return reason; }
}
