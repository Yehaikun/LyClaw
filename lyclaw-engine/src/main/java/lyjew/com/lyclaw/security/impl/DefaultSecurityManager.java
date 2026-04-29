package lyjew.com.lyclaw.security.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.security.ApprovalResult;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.security.SecurityManager;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 默认安全管理器 —— 始终返回审批通过（NONE 级别），不做安全检查。
 *
 * <p><b>作为兜底使用</b>：在不需要安全功能的场景下使用。
 * 当应用需要实际的安全策略时，实现 SecurityManager 接口并 @Component
 * 替换此默认实现。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see SecurityManager
 * @see ApprovalResult
 */
@Component
public class DefaultSecurityManager implements SecurityManager {

    @Override
    public ApprovalResult approve(ChatContext context, String action) {
        // 始终返回审批通过，沙箱级别 NONE
        return ApprovalResult.granted(SandboxLevel.NONE);
    }

    @Override
    public void revoke(String sessionId) {
        // 空操作 —— 默认实现不跟踪已审批的会话
    }

    @Override
    public boolean checkPermission(String userId, String action) {
        // 默认允许所有操作
        return true;
    }

    @Override
    public List<String> getEffectivePolicies() {
        // 默认没有生效安全策略
        return List.of("default-permissive");
    }
}