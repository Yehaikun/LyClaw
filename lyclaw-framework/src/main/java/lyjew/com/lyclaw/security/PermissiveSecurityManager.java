package lyjew.com.lyclaw.security;

import lyjew.com.lyclaw.context.ChatContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 宽松的安全管理器——默认允许所有操作。
 *
 * <p>作为默认兜底实现，当用户未提供自定义 SecurityManager 时使用。
 * 所有审批请求均通过，分配 {@link SandboxLevel#DIRECT} 级别。</p>
 */
public class PermissiveSecurityManager implements SecurityManager {

    private static final Logger log = LoggerFactory.getLogger(PermissiveSecurityManager.class);

    public PermissiveSecurityManager() {
        log.warn("[Security] PermissiveSecurityManager 已启用——所有操作将被默认允许。建议在生产环境中配置自定义 SecurityManager。");
    }

    @Override
    public ApprovalResult approve(ChatContext context, String action) {
        log.debug("[Security] 默认批准操作 action={}", action);
        return ApprovalResult.granted(SandboxLevel.DIRECT);
    }

    @Override
    public void revoke(String sessionId) {
        log.debug("[Security] revoke sessionId={}", sessionId);
    }

    @Override
    public boolean checkPermission(String userId, String action) {
        return true;
    }

    @Override
    public List<String> getEffectivePolicies() {
        return List.of("PermissivePolicy: all actions allowed by default");
    }
}
