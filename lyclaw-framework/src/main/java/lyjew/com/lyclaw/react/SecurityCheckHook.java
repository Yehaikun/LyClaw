package lyjew.com.lyclaw.react;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.filter.ContentFilter;
import lyjew.com.lyclaw.filter.FilterResult;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.security.ApprovalResult;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.security.SecurityManager;

/**
 * 安全审核 Hook，在代理调用前执行内容过滤和权限校验。
 *
 * <p>order=10，确保在所有 hook 中最先执行。
 * 拒绝时抛出 SecurityException 中断请求。
 */
public class SecurityCheckHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(SecurityCheckHook.class);

    private final SecurityManager securityManager;
    private final ContentFilter contentFilter;

    public SecurityCheckHook(SecurityManager securityManager, ContentFilter contentFilter) {
        this.securityManager = securityManager;
        this.contentFilter = contentFilter;
    }

    @Override
    public int getOrder() { return 10; }

    @Override
    public void beforeRequest(AgentContext ctx) {
        // 1. 内容过滤（提示注入检测、PII 脱敏）
        if (contentFilter != null) {
            ChatContext chatCtx = buildMinimalChatContext(ctx);
            FilterResult result = contentFilter.filter(ctx.getUserMessage(), chatCtx);
            if (!result.isPassed()) {
                log.warn("内容过滤拒绝: session={} reason={}", ctx.getSessionId(), result.getReason());
                throw new SecurityException("内容被安全策略拒绝: " + result.getReason());
            }
            ctx.setUserMessage(result.getFilteredContent());
        }

        // 2. 权限校验
        if (securityManager != null) {
            ChatContext chatCtx = buildMinimalChatContext(ctx);
            ApprovalResult approval = securityManager.approve(chatCtx, "EXECUTE_CHAT");
            if (!approval.isApproved()) {
                log.warn("安全审批拒绝: session={} reason={}", ctx.getSessionId(), approval.getReason());
                throw new SecurityException("请求被安全策略拒绝: " + approval.getReason());
            }
            SandboxLevel level = approval.getSandboxLevel();
            ctx.setSandboxLevel(level != null ? level : SandboxLevel.DIRECT);
        }
    }

    private ChatContext buildMinimalChatContext(AgentContext ctx) {
        Session session = new Session();
        session.setSessionId(ctx.getSessionId());
        List<Message> messages = ctx.getChatRequest() != null
                ? ctx.getChatRequest().getMessages() : List.of();
        session.setMessages(new java.util.ArrayList<>(messages));
        return new ChatContext(ctx.getChatRequest(), session, List.of(), null, null);
    }
}
