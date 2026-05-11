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

/**
 * 安全检查阶段，属于预处理（PREPROCESSING）组，在 ContextBuildStage 之后执行。
 *
 * <p>职责：对用户输入进行安全审核和内容过滤，确保请求合规后再进入核心编排流程。
 * 该阶段会依次执行两项检查：
 * <ol>
 *   <li><b>安全审批（SecurityManager）</b>：校验用户是否有权限执行聊天操作。</li>
 *   <li><b>内容过滤（ContentFilter）</b>：检查用户消息内容是否合规（如敏感词过滤）。</li>
 * </ol>
 * 任一检查未通过都将终止流水线，并通过 SSE 事件通知前端。
 * 如果检查过程中发生异常，会降级放行（fail-open），不阻塞后续流程。
 *
 * <p>执行顺序：第 1 位（getOrder 返回 1）。
 */
@Slf4j
@PipelineStage(name = "SecurityCheck", after = ContextBuildStage.class, group = "PREPROCESSING")
public class SecurityCheckStage extends PipelineStageBase {

    /** 安全管理器，负责权限审批 */
    private final SecurityManager securityManager;
    /** 内容过滤器，负责敏感词等合规检查 */
    private final ContentFilter contentFilter;
    /** 指标采集器，用于记录阶段耗时等监控数据 */
    private final MetricsCollector metricsCollector;

    /**
     * 构造安全检查阶段。
     *
     * @param securityManager 安全管理器，可为 null（若未配置则跳过权限检查）
     * @param contentFilter   内容过滤器，可为 null（若未配置则跳过内容检查）
     * @param metricsCollector 指标采集器，可为 null（若未配置则跳过指标记录）
     */
    public SecurityCheckStage(@org.springframework.lang.Nullable SecurityManager securityManager,
                               @org.springframework.lang.Nullable ContentFilter contentFilter,
                               @org.springframework.lang.Nullable MetricsCollector metricsCollector) {
        this.securityManager = securityManager;
        this.contentFilter = contentFilter;
        this.metricsCollector = metricsCollector;
    }

    /**
     * 执行安全检查流程。
     *
     * <p>流程：
     * <ol>
     *   <li>若流水线已被终止，直接返回空流。</li>
     *   <li>调用 SecurityManager.approve 进行权限校验。</li>
     *   <li>调用 ContentFilter.filter 进行内容合规检查。</li>
     *   <li>通过后发送 intercept_complete 事件；被拦截时发送 intercept_blocked 并终止流水线。</li>
     * </ol>
     *
     * @param context     当前聊天的上下文，包含用户请求和追踪信息
     * @param pipelineCtx 流水线上下文，用于跨阶段共享状态和控制流水线终止
     * @return SSE 事件流，包含安全检查进度和结果
     */
    @Override
    public Flux<ServerSentEvent<String>> execute(ChatContext context, PipelineContext pipelineCtx) {
        // 流水线已被上游终止，跳过本阶段
        if (pipelineCtx.isTerminated()) return Flux.empty();

        return Flux.create(sink -> {
            String traceId = context.getTracing().getTraceId();
            try {
                String userMessage = context.getRequest().getLastUserMessage();

                // 记录当前阶段名称，用于日志和追踪
                pipelineCtx.getCurrentStage().set("INTERCEPT");
                context.getTracing().beginStage("INTERCEPT");
                long t2 = System.currentTimeMillis();

                log.info(logJson("INFO", "stage_start", "INTERCEPT", traceId,
                        "Running security checks and content filter", null));
                sink.next(sseEvent("intercept_start", "Running security checks and content filter"));

                // === 安全审批检查 ===
                // 如果 securityManager 存在，则执行权限校验；否则跳过
                if (securityManager != null) {
                    long secStart = System.currentTimeMillis();
                    var approvalResult = securityManager.approve(context, "EXECUTE_CHAT");
                    long secDuration = System.currentTimeMillis() - secStart;
                    log.info(logJson("INFO", "feign_call", "INTERCEPT", traceId,
                            "securityManager.approve completed: approved=" + approvalResult.isApproved(),
                            secDuration));
                    // 审批未通过：终止流水线，通知前端拦截
                    if (!approvalResult.isApproved()) {
                        String reason = approvalResult.getReason();
                        log.warn(logJson("WARN", "stage_blocked", "INTERCEPT", traceId,
                                "Security check denied: " + reason, secDuration));
                        context.getTracing().endStage("INTERCEPT");
                        pipelineCtx.setTerminated(true); // 标记流水线终止，后续阶段将跳过
                        sink.next(sseEvent("intercept_blocked", "Security check denied: " + reason));
                        sink.next(sseEvent("done", "{\"status\":\"blocked\"}"));
                        sink.complete();
                        return;
                    }
                }

                // === 内容过滤检查 ===
                // 如果 contentFilter 存在，则检查消息内容；否则跳过
                if (contentFilter != null) {
                    long filterStart = System.currentTimeMillis();
                    FilterResult filterResult = contentFilter.filter(userMessage, context);
                    long filterDuration = System.currentTimeMillis() - filterStart;
                    log.info(logJson("INFO", "feign_call", "INTERCEPT", traceId,
                            "contentFilter.filter completed: passed=" + filterResult.isPassed(),
                            filterDuration));
                    // 内容不合规：终止流水线，通知前端拦截
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

                // 两项检查均通过，发送完成事件
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
                // 检查过程异常：采用 fail-open 策略，降级放行
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
