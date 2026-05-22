package lyjew.com.lyclaw.web.session;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.AgentHook;

/**
 * 会话持久化钩子——将SessionManager集成到Agent管道中。
 *
 * beforeAgentRun：从sessionId解析/创建Session，挂到AgentContext上。
 * modelCallEnded：LLM调用结束后将ChatRequest中所有消息持久化到Session。
 *
 * 作为AgentHook Bean自动注入AgentProxyFactory的hooks列表。
 */
public class SessionPersistenceHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(SessionPersistenceHook.class);

    private final SessionManager sessionManager;

    public SessionPersistenceHook(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
        log.info("SessionPersistenceHook 已构造");
    }

    @Override
    public void beforeAgentRun(AgentContext ctx) {
        String sessionId = ctx.getSessionId();
        String agentId = ctx.getAgentId() != null ? ctx.getAgentId() : "chat";
        log.debug("SessionPersistenceHook.beforeAgentRun: sessionId={} agentId={}", sessionId, agentId);

        Session session = sessionManager.getSession(sessionId);
        if (session == null) {
            log.warn("SessionPersistenceHook: session {} 不在缓存中，fallback创建新会话", sessionId);
            String model = ctx.getChatRequest() != null ? ctx.getChatRequest().getModel() : null;
            session = sessionManager.createSession(agentId != null ? agentId : "chat", model);
        } else {
            log.debug("SessionPersistenceHook: 从缓存获取session={} messageIndex={}", sessionId, session.getMessageIndex());
        }
        ctx.setSession(session);

        // 确保agentId一致
        if (ctx.getAgentId() == null || ctx.getAgentId().isEmpty()) {
            ctx.setAgentId(session.getAgentId());
        }
    }

    @Override
    public void modelCallEnded(AgentContext ctx) {
        log.debug("SessionPersistenceHook.modelCallEnded 被调用");
        Session session = ctx.getSession();
        if (session == null) {
            log.warn("SessionPersistenceHook.modelCallEnded: ctx.getSession() 为 null，跳过持久化");
            return;
        }

        List<Message> messages = ctx.getChatRequest().getMessages();
        if (messages == null || messages.isEmpty()) {
            log.warn("SessionPersistenceHook.modelCallEnded: messages 为空 (null={})", messages == null);
            return;
        }

        log.debug("SessionPersistenceHook: 准备持久化 sessionId={} messageIndex={} totalMessages={}",
                session.getSessionId(), session.getMessageIndex(), messages.size());

        // 每个HTTP API调用构建新的ChatRequest，消息总是本轮新增的，全部持久化
        int persisted = 0;
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg != null && msg.getRole() != null) {
                log.debug("SessionPersistenceHook: 持久化消息 idx={} role={}", i, msg.getRole());
                sessionManager.onMessage(session, msg);
                persisted++;
            }
        }
        log.info("SessionPersistenceHook: 持久化完成 sessionId={} 本轮写入{}条 messageIndex now={}",
                session.getSessionId(), persisted, session.getMessageIndex());
    }

    @Override
    public int getOrder() {
        return 10; // 尽早执行，确保Session在管道运行前就绪
    }
}
