package lyjew.com.lyclaw.pipeline.stage;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.filter.ContentFilter;
import lyjew.com.lyclaw.filter.FilterResult;
import lyjew.com.lyclaw.infra.metrics.MetricsCollector;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.security.SecurityManager;
import lyjew.com.lyclaw.annotation.PipelineStage;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全检查阶段，order=1。内容过滤 + 安全审批 → sandboxLevel。
 */
@Slf4j
@PipelineStage(name = "SecurityCheck", after = ContextBuildStage.class, group = "PREPROCESSING")
public class SecurityCheckStage extends PipelineStageBase {

    private final SecurityManager securityManager;
    private final ContentFilter contentFilter;
    private final MetricsCollector metricsCollector;

    public SecurityCheckStage(@org.springframework.lang.Nullable SecurityManager securityManager,
                               @org.springframework.lang.Nullable ContentFilter contentFilter,
                               @org.springframework.lang.Nullable MetricsCollector metricsCollector) {
        this.securityManager = securityManager;
        this.contentFilter = contentFilter;
        this.metricsCollector = metricsCollector;
    }

    private static ChatContext buildChatContext(AgentContext ctx) {
        Session session = new Session();
        session.setSessionId(ctx.getSessionId());
        List<Message> messages = ctx.getChatRequest() != null && ctx.getChatRequest().getMessages() != null
                ? ctx.getChatRequest().getMessages() : List.of();
        session.setMessages(new ArrayList<>(messages));
        return new ChatContext(ctx.getChatRequest(), session, null, List.of(), null, null);
    }

    @Override
    public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
        if (ctx.isTerminated()) return Flux.empty();

        return Flux.create(sink -> {
            String traceId = ctx.getTracing().getTraceId();
            try {
                ctx.getCurrentStage().set("INTERCEPT");
                ctx.getTracing().beginStage("INTERCEPT");
                long t2 = System.currentTimeMillis();

                log.info("\n\n══════════════════════════════════");
                log.info("  [阶段 1/5] 安全检查 - 身份验证与内容过滤 [INTERCEPT]");
                log.info("══════════════════════════════════");
                log.info(logJson("INFO", "stage_start", "INTERCEPT", traceId,
                        "Running security checks and content filter", null));
                sink.next(sseEvent("intercept_start", "Running security checks and content filter"));

                if (securityManager != null) {
                    var approvalResult = securityManager.approve(buildChatContext(ctx), "EXECUTE_CHAT");
                    if (!approvalResult.isApproved()) {
                        String reason = approvalResult.getReason();
                        log.warn(logJson("WARN", "stage_blocked", "INTERCEPT", traceId,
                                "Security check denied: " + reason, null));
                        ctx.getTracing().endStage("INTERCEPT");
                        ctx.setTerminated(true);
                        sink.next(sseEvent("intercept_blocked", "Security check denied: " + reason));
                        sink.next(sseEvent("done", "{\"status\":\"blocked\"}"));
                        sink.complete();
                        return;
                    }
                    if (approvalResult.getSandboxLevel() != null) {
                        ctx.setSandboxLevel(approvalResult.getSandboxLevel());
                        log.info(logJson("INFO", "sandbox_level", "INTERCEPT", traceId,
                                "Sandbox level: " + approvalResult.getSandboxLevel().name(), null));
                    }
                }

                if (contentFilter != null) {
                    FilterResult filterResult = contentFilter.filter(ctx.getUserMessage(), buildChatContext(ctx));
                    if (!filterResult.isPassed()) {
                        String reason = filterResult.getReason();
                        log.warn(logJson("WARN", "stage_blocked", "INTERCEPT", traceId,
                                "Content filter blocked: " + reason, null));
                        ctx.getTracing().endStage("INTERCEPT");
                        ctx.setTerminated(true);
                        sink.next(sseEvent("intercept_blocked", "Content filter blocked: " + reason));
                        sink.next(sseEvent("done", "{\"status\":\"blocked\"}"));
                        sink.complete();
                        return;
                    }
                    ctx.setUserMessage(filterResult.getFilteredContent());
                }

                sink.next(sseEvent("intercept_complete", "Security check and content filter passed"));
                long stageDuration = System.currentTimeMillis() - t2;
                ctx.getTracing().endStage("INTERCEPT");
                log.info(logJson("INFO", "stage_complete", "INTERCEPT", traceId,
                        "Intercept checks passed", stageDuration));
                if (metricsCollector != null) {
                    metricsCollector.recordPipelineStage("INTERCEPT", stageDuration);
                }
                sink.complete();
            } catch (Exception e) {
                log.warn(logJson("WARN", "stage_error", "INTERCEPT", traceId,
                        "Security check failed, continuing: " + e.getMessage(), null));
                ctx.getTracing().endStage("INTERCEPT");
                sink.next(sseEvent("intercept_complete", "Security check skipped (error)"));
                sink.complete();
            }
        });
    }

    @Override
    public int getOrder() { return 1; }

    @Override
    public String getStageName() { return "SecurityCheck"; }
}
