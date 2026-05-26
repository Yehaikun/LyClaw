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
import java.util.Map;

/**
 * 安全检查阶段，order=1。内容过滤 + 安全审批 → sandboxLevel。
 */
@Slf4j
@PipelineStage(name = "SecurityCheck", after = ContextBuildStage.class, group = "PREPROCESSING")
public class SecurityCheckStage extends PipelineStageBase {

    private final SecurityManager securityManager;
    private final List<ContentFilter> contentFilters;
    private final MetricsCollector metricsCollector;

    public SecurityCheckStage(@org.springframework.lang.Nullable SecurityManager securityManager,
                               @org.springframework.lang.Nullable List<ContentFilter> contentFilters,
                               @org.springframework.lang.Nullable MetricsCollector metricsCollector) {
        this.securityManager = securityManager;
        this.contentFilters = contentFilters;
        this.metricsCollector = metricsCollector;
    }

    private static ChatContext resolveChatContext(AgentContext ctx) {
        ChatContext cc = ctx.getChatContext();
        if (cc != null) return cc;
        Session session = new Session();
        session.setSessionId(ctx.getSessionId());
        List<Message> messages = ctx.getChatRequest() != null && ctx.getChatRequest().getMessages() != null
                ? ctx.getChatRequest().getMessages() : List.of();
        session.setMessages(new ArrayList<>(messages));
        return new ChatContext(ctx.getChatRequest(), session, List.of(), null, null);
    }

    @Override
    public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
        if (ctx.isTerminated()) {
            log.info("[安全检查] 管线已终止，跳过");
            return Flux.empty();
        }

        return Flux.defer(() -> {
            String traceId = ctx.getTracing().getTraceId();
            List<ServerSentEvent<String>> events = new ArrayList<>();
            try {
                ctx.getCurrentStage().set("INTERCEPT");
                ctx.getTracing().beginStage("INTERCEPT");
                long t2 = System.currentTimeMillis();

                log.info("\n\n========== [阶段 1] 安全检查 - 身份验证与内容过滤 [INTERCEPT] ==========");
                log.info(logJson("INFO", "stage_start", "INTERCEPT", traceId,
                        "开始执行安全检查和内容过滤", null));
                log.info("[安全检查] 开始 | sessionId={}", ctx.getSessionId());
                events.add(sseEvent("intercept_start", "正在执行安全检查和内容过滤"));

                if (securityManager != null) {
                    log.info("[安全检查] 执行安全审批 | action=EXECUTE_CHAT");
                    var approvalResult = securityManager.approve(resolveChatContext(ctx), "EXECUTE_CHAT");
                    if (!approvalResult.isApproved()) {
                        String reason = approvalResult.getReason();
                        log.warn(logJson("WARN", "stage_blocked", "INTERCEPT", traceId,
                                "安全审批拒绝: " + reason, null));
                        log.warn("[BLOCKED] [安全检查] 安全审批拒绝 | reason={}", reason);
                        ctx.getTracing().endStage("INTERCEPT");
                        ctx.setTerminated(true);
                        events.add(sseEvent("intercept_blocked", "安全审批拒绝: " + reason));
                        events.add(sseEvent("done", Map.of("status", "blocked")));
                        return Flux.fromIterable(events);
                    }
                    if (approvalResult.getSandboxLevel() != null) {
                        ctx.setSandboxLevel(approvalResult.getSandboxLevel());
                        log.info(logJson("INFO", "sandbox_level", "INTERCEPT", traceId,
                                "沙箱级别: " + approvalResult.getSandboxLevel().name(), null));
                        log.info("[安全检查] 沙箱级别={}", approvalResult.getSandboxLevel().name());
                    }
                    log.info("[OK] [安全检查] 安全审批通过");
                } else {
                    log.info("[安全检查] 无SecurityManager，跳过安全审批");
                }

                if (contentFilters != null && !contentFilters.isEmpty()) {
                    log.info("[安全检查] 执行内容过滤 | 过滤器数量={} | 原始消息长度={}",
                            contentFilters.size(),
                            ctx.getUserMessage() != null ? ctx.getUserMessage().length() : 0);
                    String currentContent = ctx.getUserMessage();
                    for (ContentFilter filter : contentFilters) {
                        if (currentContent == null) break;
                        log.info("[安全检查] 应用过滤器: {}", filter.getClass().getSimpleName());
                        FilterResult filterResult = filter.filter(currentContent, resolveChatContext(ctx));
                        if (!filterResult.isPassed()) {
                            String reason = filterResult.getReason();
                            log.warn(logJson("WARN", "stage_blocked", "INTERCEPT", traceId,
                                    "内容过滤拦截(" + filter.getClass().getSimpleName() + "): " + reason, null));
                            log.warn("[BLOCKED] [安全检查] 内容过滤拦截 | filter={} reason={}",
                                    filter.getClass().getSimpleName(), reason);
                            ctx.getTracing().endStage("INTERCEPT");
                            ctx.setTerminated(true);
                            events.add(sseEvent("intercept_blocked", "内容过滤拦截: " + reason));
                            events.add(sseEvent("done", Map.of("status", "blocked")));
                            return Flux.fromIterable(events);
                        }
                        currentContent = filterResult.getFilteredContent();
                    }
                    ctx.setUserMessage(currentContent);
                    log.info("[OK] [安全检查] 所有过滤器通过 | 过滤后消息长度={}",
                            currentContent != null ? currentContent.length() : 0);
                } else {
                    log.info("[安全检查] 无ContentFilter，跳过内容过滤");
                }

                events.add(sseEvent("intercept_complete", "安全检查和内容过滤已通过"));
                long stageDuration = System.currentTimeMillis() - t2;
                ctx.getTracing().endStage("INTERCEPT");
                log.info(logJson("INFO", "stage_complete", "INTERCEPT", traceId,
                        "安全检查通过", stageDuration));
                log.info("[OK] [安全检查] 阶段完成 | 总耗时={}ms", stageDuration);
                if (metricsCollector != null) {
                    metricsCollector.recordPipelineStage("INTERCEPT", stageDuration);
                }
            } catch (Exception e) {
                log.warn(logJson("WARN", "stage_error", "INTERCEPT", traceId,
                        "安全检查失败，继续执行: " + e.getMessage(), null), e);
                log.warn("[WARN] [安全检查] 阶段异常（降级继续）| error={}", e.getMessage());
                ctx.getTracing().endStage("INTERCEPT");
                events.add(sseEvent("intercept_complete", "安全检查跳过（异常）"));
            }
            return Flux.fromIterable(events);
        });
    }

    @Override
    public int getOrder() { return 1; }

    @Override
    public String getStageName() { return "SecurityCheck"; }
}
