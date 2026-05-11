package lyjew.com.lyclaw.orchestration.stage;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.pipeline.PipelineContext;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

@Slf4j
@Component
public class RespondStage extends PipelineStageBase {

    private final ModelProvider modelProvider;

    public RespondStage(ModelProvider modelProvider) {
        this.modelProvider = modelProvider;
    }

    @Override
    public Flux<ServerSentEvent<String>> execute(ChatContext context, PipelineContext pipelineCtx) {
        if (pipelineCtx.isTerminated() || !pipelineCtx.isPipelineOk()) {
            return Flux.empty();
        }

        return Flux.defer(() -> {
            String traceId = context.getTracing().getTraceId();
            int sc = pipelineCtx.getSuccessCount().get();
            int fc = pipelineCtx.getFailCount().get();
            ReflectionReport report = pipelineCtx.getReportRef().get();
            double score = pipelineCtx.getReflectScoreRef().get();
            List<String> toolResults = pipelineCtx.getToolResults();
            long orchestrationStart = System.currentTimeMillis() - 0; // approximate

            log.info(logJson("INFO", "stage_start", "RESPOND", traceId,
                    "Generating AI response", null));
            pipelineCtx.getCurrentStage().set("RESPOND");
            context.getTracing().beginStage("RESPOND");

            ModelAdapter adapter = modelProvider.getConfiguredAdapter();

            Flux<ServerSentEvent<String>> bodyFlux;
            if (adapter != null) {
                lyjew.com.lyclaw.model.ChatRequest llmRequest = buildLlmRequest(context, toolResults);
                log.info(logJson("INFO", "llm_call", "RESPOND", traceId,
                        "Calling LLM: provider=" + adapter.getProvider()
                                + " model=" + adapter.getModel()
                                + " messages=" + llmRequest.getMessageCount(),
                        null));

                bodyFlux = adapter.chatStream(llmRequest)
                        .handle((line, sink) -> {
                            String text = adapter.extractSsePlainText(line);
                            if (!text.isEmpty()) {
                                sink.next(sseEvent("message", text));
                            }
                        });
            } else {
                log.warn(logJson("WARN", "llm_missing", "RESPOND", traceId,
                        "No LLM adapter configured, using hardcoded response", null));
                String responseText = buildFinalResponse(sc, fc, toolResults, report);
                bodyFlux = Flux.just(sseEvent("message", responseText));
            }

            return Flux.just(sseEvent("respond_start", "Generating AI response"))
                    .concatWith(bodyFlux)
                    .onErrorResume(err -> {
                        context.getTracing().endStage("RESPOND");
                        long elapsed = System.currentTimeMillis() - orchestrationStart;
                        log.error(logJson("ERROR", "stage_error", "RESPOND", traceId,
                                "LLM call failed: " + err.getMessage(), elapsed), err);
                        String fallback = buildFinalResponse(sc, fc, toolResults, report);
                        pipelineCtx.setTerminated(true); // signal MetricsStage to emit fallback tail
                        return Flux.just(
                                sseEvent("message", fallback),
                                sseEvent("done", "{\"status\":\"completed\",\"fallback\":true}")
                        );
                    });
        });
    }

    @Override
    public int getOrder() { return 4; }

    @Override
    public String getStageName() { return "Respond"; }
}
