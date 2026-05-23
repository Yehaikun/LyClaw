package lyjew.com.lyclaw.pipeline.stage;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.action.ActionResult;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.infra.metrics.MetricsCollector;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.reflect.ReflectionEngine;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import lyjew.com.lyclaw.annotation.PipelineStage;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * 反思评估阶段，在 RespondStage 之后执行。
 * 评估 ReAct 响应的质量，存储评分供重试闭环使用。
 */
@Slf4j
@PipelineStage(name = "Reflection", after = RespondStage.class, group = "POSTPROCESSING")
public class ReflectionStage extends PipelineStageBase {

    private final ReflectionEngine reflectionEngine;
    private final MetricsCollector metricsCollector;

    public ReflectionStage(
            @org.springframework.lang.Nullable ReflectionEngine reflectionEngine,
            @org.springframework.lang.Nullable MetricsCollector metricsCollector) {
        this.reflectionEngine = reflectionEngine;
        this.metricsCollector = metricsCollector;
    }

    private static ChatContext buildChatContext(AgentContext ctx) {
        Session session = new Session();
        session.setSessionId(ctx.getSessionId());
        List<Message> messages = ctx.getChatRequest() != null && ctx.getChatRequest().getMessages() != null
                ? ctx.getChatRequest().getMessages() : List.of();
        session.setMessages(new ArrayList<>(messages));
        ChatContext chatCtx = new ChatContext(ctx.getChatRequest(), session, null, List.of(), null, null);
        chatCtx.setAttribute("memoryEntries", ctx.getAttribute("memoryEntries"));
        return chatCtx;
    }

    @Override
    public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
        if (ctx.isTerminated()) {
            log.info("[反思评估] 管线已终止，跳过");
            return Flux.empty();
        }
        if (!ctx.isPipelineOk()) {
            log.warn("[反思评估] 管线状态异常(pipelineOk=false)，跳过");
            return Flux.empty();
        }

        return Flux.defer(() -> {
            String traceId = ctx.getTracing().getTraceId();
            List<ServerSentEvent<String>> events = new ArrayList<>();
            try {
                ctx.getCurrentStage().set("REFLECTION");
                ctx.getTracing().beginStage("REFLECTION");
                long t = System.currentTimeMillis();

                log.info("\n\n========== [阶段 3] 反思评估 [REFLECTION] ==========");
                log.info(logJson("INFO", "stage_start", "REFLECTION", traceId,
                        "开始评估响应质量", null));
                events.add(sseEvent("reflection_start", "正在评估响应质量"));

                String finalResponse = ctx.getAttribute("finalResponse");
                List<String> toolResults = ctx.getToolResults();
                int successCount = ctx.getSuccessCount().get();
                int failCount = ctx.getFailCount().get();
                log.info("[反思评估] 收集评估数据 | 响应长度={} | 工具调用成功={} 失败={}",
                        finalResponse != null ? finalResponse.length() : 0, successCount, failCount);

                ActionResult result = ActionResult.builder()
                        .nodeId(ctx.getSessionId())
                        .success(failCount == 0)
                        .output(finalResponse != null ? finalResponse : "")
                        .metadata(Map.of(
                                "toolResults", toolResults != null ? toolResults : List.of(),
                                "successCount", successCount,
                                "failCount", failCount))
                        .build();

                double score;
                int errorCount = 0;
                boolean needsRetry = false;

                if (reflectionEngine != null) {
                    log.info("[反思评估] 调用ReflectionEngine进行评估...");
                    ChatContext chatCtx = buildChatContext(ctx);
                    ReflectionReport report = reflectionEngine.reflect(chatCtx, result);
                    score = report.getOverallScore();

                    if (report.getErrors() != null) {
                        errorCount = report.getErrors().size();
                    }

                    if (score < 0.6 && (failCount > 0 || (report.getErrors() != null && !report.getErrors().isEmpty()))) {
                        needsRetry = true;
                    }

                    ctx.getReflectScoreRef().set(score);

                    log.info(logJson("INFO", "reflection_result", "REFLECTION", traceId,
                            String.format("评分=%.2f 错误数=%d 需重试=%s",
                                    score, errorCount, needsRetry), null));
                    log.info("{} [反思评估] 评估完成 | 评分={} | 错误数={} | 需重试={}",
                            needsRetry ? "[WARN]" : "[OK]", String.format("%.2f", score), errorCount, needsRetry);
                } else {
                    score = 1.0;
                    log.info(logJson("INFO", "reflection_skipped", "REFLECTION", traceId,
                            "无ReflectionEngine可用，跳过评估", null));
                    log.info("[反思评估] 无ReflectionEngine，跳过评估（默认满分）");
                }

                Map<String, Object> reflectionData = new LinkedHashMap<>();
                reflectionData.put("score", score);
                reflectionData.put("errors", errorCount);
                reflectionData.put("needsRetry", needsRetry);
                events.add(sseEvent("reflection_complete", reflectionData));

                long stageDuration = System.currentTimeMillis() - t;
                ctx.getTracing().endStage("REFLECTION");
                log.info("[OK] [反思评估] 阶段完成 | 总耗时={}ms", stageDuration);
                if (metricsCollector != null) {
                    metricsCollector.recordPipelineStage("REFLECTION", stageDuration);
                }

            } catch (Exception e) {
                log.warn(logJson("WARN", "stage_error", "REFLECTION", traceId,
                        "反思评估失败，继续执行: " + e.getMessage(), null), e);
                log.warn("[WARN] [反思评估] 阶段异常（降级继续）| error={}", e.getMessage());
                ctx.getReflectScoreRef().set(0.5);
                events.add(sseEvent("reflection_complete",
                        Map.of("score", 0.0, "errors", 0, "degraded", true)));
            }
            return Flux.fromIterable(events);
        });
    }

    @Override
    public int getOrder() { return 4; }

    @Override
    public String getStageName() { return "Reflection"; }
}
