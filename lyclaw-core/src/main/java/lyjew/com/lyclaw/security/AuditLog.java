package lyjew.com.lyclaw.security;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class AuditLog {
    private String logId;
    private String userId;
    private String sessionId;
    private String action;
    private String target;
    private PermissionLevel requiredLevel;
    private boolean approved;
    private String reason;
    private Instant timestamp;
    private String previousHash;
    private String currentHash;
    private Map<String, Object> metadata;

    public String computeHash() {
        String data = (previousHash != null ? previousHash : "") + action + target + timestamp;
        return Integer.toHexString(data.hashCode());
    }
}
