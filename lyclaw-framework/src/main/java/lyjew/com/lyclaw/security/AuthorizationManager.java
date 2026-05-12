package lyjew.com.lyclaw.security;

import lyjew.com.lyclaw.context.ChatContext;

import java.util.List;

/**
 * 授权管理器接口，负责操作审批、权限校验和策略管理。
 */
public interface AuthorizationManager {

    /** 审批指定操作，返回审批结果和沙箱级别。 */
    ApprovalResult approve(ChatContext context, String action);

    /** 撤销会话的所有已授予权限。 */
    void revoke(String sessionId);

    /** 检查用户是否拥有执行指定操作的权限。 */
    boolean checkPermission(String userId, String action);

    /** 检查用户是否满足指定权限级别。 */
    default boolean checkPermission(String userId, String action, PermissionLevel requiredLevel) {
        return checkPermission(userId, action);
    }

    /** @return 当前生效的安全策略列表 */
    List<String> getEffectivePolicies();
}
