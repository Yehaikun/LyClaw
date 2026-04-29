package lyjew.com.lyclaw.security.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.security.ApprovalResult;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.security.SecurityManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * SecurityManager 空对象实现 —— approve 始终返回 granted(NONE)，
 * revoke 空操作，checkPermission 始终返回 true。
 *
 * <p>当应用不需要安全功能时，注入此实现避免 NPE。</p>
 *
 * <p><b>Spring 注入</b>：@Component + @ConditionalOnMissingBean(SecurityManager.class)，
 * 当没有其他 SecurityManager 实现时自动使用此空对象。</p>
 *
 import org.springframework.stereotype.Component;
 import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
 * @since 1.0
 * @author LyClaw Team
 */
@Component
@ConditionalOnMissingBean(SecurityManager.class)
public class NullSecurityManager implements SecurityManager {

    @Override
    public ApprovalResult approve(ChatContext context, String action) {
        return ApprovalResult.granted(SandboxLevel.NONE);
    }

    @Override
    public void revoke(String sessionId) { /* 空操作 */ }

    @Override
    public boolean checkPermission(String userId, String action) {
        return true;
    }

    @Override
    public List<String> getEffectivePolicies() {
        return Collections.emptyList();
    }
}