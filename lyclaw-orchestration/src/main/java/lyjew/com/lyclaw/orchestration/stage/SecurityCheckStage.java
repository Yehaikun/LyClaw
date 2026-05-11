package lyjew.com.lyclaw.orchestration.stage;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.filter.ContentFilter;
import lyjew.com.lyclaw.filter.FilterResult;
import lyjew.com.lyclaw.infra.metrics.MetricsCollector;
import lyjew.com.lyclaw.pipeline.PipelineContext;
import lyjew.com.lyclaw.security.SecurityManager;
import org.springframework.http.codec.ServerSentEvent;
import lyjew.com.lyclaw.framework.annotation.PipelineStage;
import reactor.core.publisher.Flux;

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

    @Override
    public Flux<ServerSentEvent<String>> execute(ChatContext context, PipelineContext pipelineCtx) {
        if (pipelineCtx.isTerminated()) return Flux.empty();

        return Flux.create(sink -> {
            String traceId = context.getTracing().getTraceId();
            try {
                String userMessage = context.getRequest().getLastUserMessage();

                pipelineCtx.getCurrentStage().set("INTERCEPT");
                context.getTracing().beginStage("INTERCEPT");
                long t2 = System.currentTimeMillis();

                log.info(logJson("INFO", "stage_start", "INTERCEPT", traceId,
                        "Running security checks and content filter", null));
                sink.next(sseEvent("intercept_start", "Running security checks and content filter"));

                // Security check
                if (securityManager != null) {
                    long secStart = System.currentTimeMillis();
                    var approvalResult = securityManager.approve(context, "EXECUTE_CHAT");
                    long secDuration = System.currentTimeMillis() - secStart;
                    log.info(logJson("INFO", "feign_call", "INTERCEPT", traceId,
                            "securityManager.approve completed: approved=" + approvalResult.isApproved(),
                            secDuration));
                    if (!approvalResult.isApproved()) {
                        String reason = approvalResult.getReason();
                        log.warn(logJson("WARN", "stage_blocked", "INTERCEPT", traceId,
                                "Security check denied: " + reason, secDuration));
                        context.getTracing().endStage("INTERCEPT");
                        pipelineCtx.setTerminated(true);
                        sink.next(sseEvent("intercept_blocked", "Security check denied: " + reason));
                        sink.next(sseEvent("done", "{\"status\":\"blocked\"}"));
                        sink.complete();
                        return;
                    }
                }

                // Content filter
                if (contentFilter != null) {
                    long filterStart = System.currentTimeMillis();
                    FilterResult filterResult = contentFilter.filter(userMessage, context);
                    long filterDuration = System.currentTimeMillis() - filterStart;
                    log.info(logJson("INFO", "feign_call", "INTERCEPT", traceId,
                            "contentFilter.filter completed: passed=" + filterResult.isPassed(),
                            filterDuration));
                    if (!filterResult.isPassed()) {
                        String reason = filterResult.getReason();
                        log.warn(logJson("WARN", "stage_blocked", "INTERCEPT", traceId,
                                "Content filter blocked: " + reason, filterDuration));
                        context.getTracing().endStage("INTERCEPT");
                        pipelineCtx.setTerminated(true);
                        sink.next(sseEvent("intercept_blocked", "Content filter blocked: " + reason));
                        sink.next(sseEvent("done", "{\"status\":\"blocked\"}"));
                        sink.complete();
                        return;
                    }
                }

                sink.next(sseEvent("intercept_complete", "Security check and content filter passed"));
                long stage2Duration = System.currentTimeMillis() - t2;
                context.getTracing().endStage("INTERCEPT");
                log.info(logJson("INFO", "stage_complete", "INTERCEPT", traceId,
                        "Intercept checks passed", stage2Duration));
                if (metricsCollector != null) {
                    metricsCollector.recordPipelineStage("INTERCEPT", stage2Duration);
                }
                sink.complete();
            } catch (Exception e) {
                log.warn(logJson("WARN", "stage_error", "INTERCEPT", traceId,
                        "Security check failed, continuing: " + e.getMessage(), null));
                context.getTracing().endStage("INTERCEPT");
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
