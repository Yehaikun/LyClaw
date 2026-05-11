package lyjew.com.lyclaw.orchestration.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.orchestration.AgentEvent;
import lyjew.com.lyclaw.orchestration.OrchestrationContext;
import lyjew.com.lyclaw.orchestration.Orchestrator;
import lyjew.com.lyclaw.orchestration.pipeline.PipelineBuilder;
import lyjew.com.lyclaw.pipeline.PipelineContext;
import lyjew.com.lyclaw.pipeline.ReactivePipeline;
import org.slf4j.MDC;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class OrchestratorImpl implements Orchestrator {

    private final PipelineBuilder pipelineBuilder;

    private final ConcurrentHashMap<String, Boolean> cancellationFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> progressTracker = new ConcurrentHashMap<>();

    public OrchestratorImpl(PipelineBuilder pipelineBuilder) {
        this.pipelineBuilder = pipelineBuilder;
    }

    @Override
    public Flux<ServerSentEvent<String>> execute(ChatContext context) {
        return Flux.defer(() -> {
            String traceId = context.getTracing().getTraceId();
            MDC.put("traceId", traceId);

            PipelineContext pipelineCtx = new PipelineContext();
            context.setAttribute("pipelineContext", pipelineCtx);

            ReactivePipeline pipeline = pipelineBuilder.buildReactive();
            return pipeline.execute(context, pipelineCtx)
                    .onErrorResume(err -> {
                        String failedStage = pipelineCtx.getCurrentStage().get();
                        context.getTracing().endStage(failedStage);
                        log.error("[Orchestrator] Pipeline error at stage {}: {}",
                                failedStage, err.getMessage(), err);
                        return Flux.just(
                                sseEvent("error",
                                        "{\"message\":\"" + escapeJson(err.getMessage())
                                                + "\",\"traceId\":\"" + escapeJson(traceId)
                                                + "\",\"stage\":\"" + escapeJson(failedStage) + "\"}"),
                                sseEvent("done", "{\"status\":\"error\"}")
                        );
                    })
                    .doFinally(signalType -> MDC.remove("traceId"));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<AgentEvent> executeAgentTask(OrchestrationContext context) {
        String collabId = context.getCollaborationId() != null
                ? context.getCollaborationId()
                : "collab-" + UUID.randomUUID().toString().substring(0, 8);

        log.info("[Orchestrator] Executing agent task: collabId={}, modeId={}, tasks={}",
                collabId, context.getCollaborationModeId(),
                context.getTasks() != null ? context.getTasks().size() : 0);

        return Flux.create(sink -> {
            try {
                sink.next(AgentEvent.builder()
                        .type(AgentEvent.EventType.COLLABORATION_STARTED)
                        .agentId("orchestrator")
                        .data("Collaboration started: " + collabId)
                        .metadata(Map.of("collaborationId", collabId,
                                "modeId", context.getCollaborationModeId(),
                                "taskCount", context.getTasks() != null ? context.getTasks().size() : 0))
                        .timestamp(System.currentTimeMillis())
                        .build());

                sink.next(AgentEvent.builder()
                        .type(AgentEvent.EventType.TASK_STARTED)
                        .agentId("agent-0")
                        .data("Task execution started")
                        .metadata(Map.of("collaborationId", collabId))
                        .timestamp(System.currentTimeMillis())
                        .build());

                if (context.getTasks() != null) {
                    for (int i = 0; i < context.getTasks().size(); i++) {
                        var task = context.getTasks().get(i);
                        sink.next(AgentEvent.builder()
                                .type(AgentEvent.EventType.TASK_STARTED)
                                .agentId("agent-" + i)
                                .data("Task " + task.getTaskId() + " started: " + task.getType())
                                .metadata(Map.of("taskId", task.getTaskId(),
                                        "type", task.getType(),
                                        "target", task.getTarget() != null ? task.getTarget() : ""))
                                .timestamp(System.currentTimeMillis())
                                .build());

                        sink.next(AgentEvent.builder()
                                .type(AgentEvent.EventType.TASK_COMPLETED)
                                .agentId("agent-" + i)
                                .data("Task " + task.getTaskId() + " completed")
                                .metadata(Map.of("taskId", task.getTaskId()))
                                .timestamp(System.currentTimeMillis())
                                .build());
                    }
                }

                sink.next(AgentEvent.builder()
                        .type(AgentEvent.EventType.COLLABORATION_ENDED)
                        .agentId("orchestrator")
                        .data("Collaboration ended: " + collabId)
                        .timestamp(System.currentTimeMillis())
                        .build());
                sink.complete();
            } catch (Exception e) {
                log.error("[Orchestrator] Agent task execution failed: {}", e.getMessage(), e);
                sink.next(AgentEvent.builder()
                        .type(AgentEvent.EventType.TASK_FAILED)
                        .agentId("orchestrator")
                        .data("Agent task failed: " + e.getMessage())
                        .timestamp(System.currentTimeMillis())
                        .build());
                sink.complete();
            }
        });
    }

    @Override
    public boolean cancel(String collaborationId) {
        if (collaborationId == null) return false;
        Boolean existing = cancellationFlags.put(collaborationId, true);
        if (existing != null && existing) {
            log.info("[Orchestrator] Cancellation flag already set for: {}", collaborationId);
            return false;
        }
        progressTracker.remove(collaborationId);
        log.info("[Orchestrator] Cancellation requested for: {}", collaborationId);
        return true;
    }

    @Override
    public double getProgress(String collaborationId) {
        return progressTracker.getOrDefault(collaborationId, 0.0);
    }

    // --- retained helper methods for error handling in execute() ---

    private ServerSentEvent<String> sseEvent(String eventType, String payload) {
        return ServerSentEvent.<String>builder().event(eventType).data(payload).build();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
