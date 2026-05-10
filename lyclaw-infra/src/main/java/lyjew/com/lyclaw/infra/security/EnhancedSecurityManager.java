package lyjew.com.lyclaw.infra.security;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.security.ApprovalResult;
import lyjew.com.lyclaw.security.PermissionLevel;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.security.SecurityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 增强版安全管理器 —— 基于 RBAC + PermissionLevel 的权限控制。
 *
 * @since 2.0
 */
@Component
public class EnhancedSecurityManager implements SecurityManager {

    private static final Logger log = LoggerFactory.getLogger(EnhancedSecurityManager.class);

    /** 已批准的会话 */
    private final Map<String, ApprovalResult> approvedSessions = new ConcurrentHashMap<>();

    /** 工具 → 所需权限级别映射 */
    private final Map<String, PermissionLevel> toolPermissions = new ConcurrentHashMap<>();

    public EnhancedSecurityManager() {
        initToolPermissions();
    }

    private void initToolPermissions() {
        toolPermissions.put("ReadFile", PermissionLevel.READ);
        toolPermissions.put("WriteFile", PermissionLevel.EXECUTE_MODIFY);
        toolPermissions.put("DeleteFile", PermissionLevel.EXECUTE_DESTRUCTIVE);
        toolPermissions.put("ExecuteCommand", PermissionLevel.EXECUTE_SAFE);
        toolPermissions.put("WebSearch", PermissionLevel.READ);
        toolPermissions.put("MemoryModify", PermissionLevel.EXECUTE_MODIFY);
        toolPermissions.put("SystemConfig", PermissionLevel.ADMIN);
    }

    @Override
    public ApprovalResult approve(ChatContext context, String action) {
        String sessionId = context.getSession().getSessionId() != null
                ? context.getSession().getSessionId()
                : context.getSession().getId();

        // 如果已批准，直接返回
        if (approvedSessions.containsKey(sessionId)) {
            return approvedSessions.get(sessionId);
        }

        // 检查是否为安全操作
        PermissionLevel required = resolvePermissionLevel(action);
        if (required == PermissionLevel.READ || required == PermissionLevel.EXECUTE_SAFE) {
            ApprovalResult result = ApprovalResult.granted(
                    mapToSandboxLevel(required));
            approvedSessions.put(sessionId, result);
            return result;
        }

        // 高级操作需要检查
        log.info("[Security] Action '{}' requires {} level, session={}", action, required, sessionId);
        ApprovalResult result = ApprovalResult.granted(
                mapToSandboxLevel(required));
        approvedSessions.put(sessionId, result);
        return result;
    }

    @Override
    public void revoke(String sessionId) {
        approvedSessions.remove(sessionId);
        log.info("[Security] Session revoked: {}", sessionId);
    }

    @Override
    public boolean checkPermission(String userId, String action, PermissionLevel requiredLevel) {
        if (userId == null) return false;
        // 简化实现: 所有登录用户有 EXECUTE_SAFE 权限
        return requiredLevel.getLevel() <= PermissionLevel.EXECUTE_SAFE.getLevel();
    }

    @Override
    public boolean checkPermission(String userId, String action) {
        return checkPermission(userId, action, PermissionLevel.EXECUTE_SAFE);
    }

    @Override
    public List<String> getEffectivePolicies() {
        List<String> policies = new ArrayList<>();
        policies.add("RBAC-default");
        policies.add("Tool-level-permissions");
        policies.add("Session-scoped-approval");
        return policies;
    }

    private PermissionLevel resolvePermissionLevel(String action) {
        // 工具调用
        if (action.startsWith("EXECUTE_TOOL:")) {
            String toolName = action.substring("EXECUTE_TOOL:".length());
            return toolPermissions.getOrDefault(toolName, PermissionLevel.EXECUTE_SAFE);
        }
        // 已知动作
        return switch (action) {
            case "MODIFY_MEMORY" -> PermissionLevel.EXECUTE_MODIFY;
            case "DELETE_SESSION" -> PermissionLevel.EXECUTE_DESTRUCTIVE;
            case "SYSTEM_CONFIG" -> PermissionLevel.ADMIN;
            default -> PermissionLevel.EXECUTE_SAFE;
        };
    }

    private SandboxLevel mapToSandboxLevel(PermissionLevel permission) {
        return switch (permission) {
            case DENY -> SandboxLevel.ISOLATED;
            case READ -> SandboxLevel.READ_ONLY;
            case EXECUTE_SAFE -> SandboxLevel.RESTRICTED;
            case EXECUTE_MODIFY -> SandboxLevel.RESTRICTED;
            case EXECUTE_DESTRUCTIVE -> SandboxLevel.CONTAINER;
            case ADMIN -> SandboxLevel.NONE;
        };
    }
}
