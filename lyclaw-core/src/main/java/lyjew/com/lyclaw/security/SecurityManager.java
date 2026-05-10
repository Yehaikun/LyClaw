package lyjew.com.lyclaw.security;

import lyjew.com.lyclaw.context.ChatContext;

import java.util.List;

public interface SecurityManager {

    ApprovalResult approve(ChatContext context, String action);

    void revoke(String sessionId);

    boolean checkPermission(String userId, String action);

    default boolean checkPermission(String userId, String action, PermissionLevel requiredLevel) {
        return checkPermission(userId, action);
    }

    List<String> getEffectivePolicies();
}
