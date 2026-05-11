package lyjew.com.lyclaw.orchestration.stage;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.feign.ReflectFeignClient;
import lyjew.com.lyclaw.infra.metrics.MetricsCollector;
import lyjew.com.lyclaw.pipeline.PipelineContext;
import lyjew.com.lyclaw.reflect.ReflectRequest;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import org.springframework.http.codec.ServerSentEvent;
import lyjew.com.lyclaw.framework.annotation.PipelineStage;
import reactor.core.publisher.Flux;

@Slf4j
@PipelineStage(name = "Reflection", after = PlanExecutionStage.class, group = "CORE")
public class ReflectionStage extends PipelineStageBase {

    private final ReflectFeignClient reflectFeignClient;
    private final MetricsCollector metricsCollector;

    public ReflectionStage(ReflectFeignClient reflectFeignClient,
                            @org.springframework.lang.Nullable MetricsCollector metricsCollector) {
        this.reflectFeignClient = reflectFeignClient;
        this.metricsCollector = metricsCollector;
    }

    @Override
    public Flux<ServerSentEvent<String>> execute(ChatContext context, PipelineContext pipelineCtx) {
        if (pipelineCtx.isTerminated()) return Flux.empty();

        return Flux.create(sink -> {
            String traceId = context.getTracing().getTraceId();
            try {
                String sessionId = context.getRequest().getSessionId();
                String userMessage = context.getRequest().getLastUserMessage();
                java.util.List<String> toolResults = pipelineCtx.getToolResults();

                pipelineCtx.getCurrentStage().set("REFLECT");
                context.getTracing().beginStage("REFLECT");
                long t5 = System.currentTimeMillis();

                log.info(logJson("INFO", "stage_start", "REFLECT", traceId,
                        "Reflecting on execution results", null));
                sink.next(sseEvent("reflect_start", "Reflecting on execution results"));

                String combinedOutput = String.join("\n", toolResults);
                ReflectRequest reflectReq = ReflectRequest.builder()
                        .sessionId(sessionId)
                        .output(combinedOutput.isEmpty() ? userMessage : combinedOutput)
                        .context("Orchestration pipeline execution - " + pipelineCtx.getNodes().size() + " tasks processed")
                        .build();
                long reflectCallStart = System.currentTimeMillis();
                ReflectionReport r = reflectFeignClient.reflect(reflectReq);
                long reflectCallDuration = System.currentTimeMillis() - reflectCallStart;
                log.info(logJson("INFO", "feign_call", "REFLECT", traceId,
                        "reflectFeignClient.reflect completed", reflectCallDuration));

                pipelineCtx.getReportRef().set(r);
                double score = r != null ? r.getOverallScore() : 0.0;
                pipelineCtx.getReflectScoreRef().set(score);

                log.info(logJson("INFO", "reflect_result", "REFLECT", traceId,
                        "Reflection complete: score=" + score, null));
                sink.next(sseEvent("reflect_complete",
                        "{\"score\":" + score + ",\"reflectionId\":\""
                                + (r != null ? r.getReflectionId() : "N/A") + "\"}"));

                long stage5Duration = System.currentTimeMillis() - t5;
                context.getTracing().endStage("REFLECT");
                log.info(logJson("INFO", "stage_complete", "REFLECT", traceId,
                        "Reflection complete, score=" + score, stage5Duration));
                if (metricsCollector != null) {
                    metricsCollector.recordPipelineStage("REFLECT", stage5Duration);
                }

                pipelineCtx.getRespondStartMs().set(System.currentTimeMillis());
                pipelineCtx.setPipelineOk(true);
                pipelineCtx.getCurrentStage().set("pipeline_done");

                sink.complete();
            } catch (Exception e) {
                log.warn(logJson("WARN", "stage_error", "REFLECT", traceId,
                        "Reflection failed, continuing degraded: " + e.getMessage(), null));
                pipelineCtx.getReportRef().set(null);
                pipelineCtx.getReflectScoreRef().set(0.0);
                pipelineCtx.setPipelineOk(true);
                pipelineCtx.getCurrentStage().set("pipeline_done");
                pipelineCtx.getRespondStartMs().set(System.currentTimeMillis());
                sink.next(sseEvent("reflect_complete", "{\"score\":0.0,\"reflectionId\":\"degraded\"}"));
                sink.complete();
            }
        });
    }

    @Override
    public int getOrder() { return 3; }

    @Override
    public String getStageName() { return "Reflection"; }
}
