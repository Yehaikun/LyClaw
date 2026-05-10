package lyjew.com.lyclaw.orchestration.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.action.ToolExecuteRequest;
import lyjew.com.lyclaw.action.tool.ToolResult;
import lyjew.com.lyclaw.agent.collab.CollaborationHub;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.feign.ActionFeignClient;
import lyjew.com.lyclaw.feign.MemoryFeignClient;
import lyjew.com.lyclaw.feign.PlanFeignClient;
import lyjew.com.lyclaw.feign.ReflectFeignClient;
import lyjew.com.lyclaw.filter.ContentFilter;
import lyjew.com.lyclaw.filter.FilterResult;
import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.infra.metrics.MetricsCollector;
import lyjew.com.lyclaw.memory.MemoryQuery;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.memory.MemoryQueryResult;
import lyjew.com.lyclaw.memory.PerceptionData;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.orchestration.AgentEvent;
import lyjew.com.lyclaw.orchestration.OrchestrationContext;
import lyjew.com.lyclaw.orchestration.Orchestrator;
import lyjew.com.lyclaw.reflect.ReflectRequest;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import lyjew.com.lyclaw.security.SecurityManager;
import lyjew.com.lyclaw.task.PlanRequest;
import lyjew.com.lyclaw.task.TaskNode;
import java.util.ArrayList;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class OrchestratorImpl implements Orchestrator {

    private final PlanFeignClient planFeignClient;
    private final ActionFeignClient actionFeignClient;
    private final ReflectFeignClient reflectFeignClient;
    private final MemoryFeignClient memoryFeignClient;
    private final SecurityManager securityManager;
    private final ContentFilter contentFilter;
    private final MetricsCollector metricsCollector;
    private final CollaborationHub collaborationHub;
    private final ModelProvider modelProvider;

    private final ConcurrentHashMap<String, Boolean> cancellationFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> progressTracker = new ConcurrentHashMap<>();

    public OrchestratorImpl(PlanFeignClient planFeignClient,
                            ActionFeignClient actionFeignClient,
                            ReflectFeignClient reflectFeignClient,
                            MemoryFeignClient memoryFeignClient,
                            SecurityManager securityManager,
                            ContentFilter contentFilter,
                            MetricsCollector metricsCollector,
                            CollaborationHub collaborationHub,
                            ModelProvider modelProvider) {
        this.planFeignClient = planFeignClient;
        this.actionFeignClient = actionFeignClient;
        this.reflectFeignClient = reflectFeignClient;
        this.memoryFeignClient = memoryFeignClient;
        this.securityManager = securityManager;
        this.contentFilter = contentFilter;
        this.metricsCollector = metricsCollector;
        this.collaborationHub = collaborationHub;
        this.modelProvider = modelProvider;
    }

    @Override
    public Flux<ServerSentEvent<String>> execute(ChatContext context) {
        return Flux.defer(() -> {
            long orchestrationStart = System.currentTimeMillis();
            ChatRequest request = context.getRequest();
            String sessionId = request.getSessionId();
            String userMessage = request.getLastUserMessage();

            // Shared mutable state — written by pipeline, read by respond
            final List<String> toolResults = new ArrayList<>();
            final AtomicInteger successCount = new AtomicInteger(0);
            final AtomicInteger failCount = new AtomicInteger(0);
            final List<TaskNode> nodes = new ArrayList<>();
            final AtomicReference<ReflectionReport> reportRef =
                    new AtomicReference<>();
            final AtomicReference<Double> reflectScoreRef =
                    new AtomicReference<>(0.0);
            final AtomicBoolean pipelineOk =
                    new AtomicBoolean(false);
            final AtomicLong respondStartMs =
                    new AtomicLong();

            // ---- Stages 1–5: synchronous pipeline ----
            Flux<ServerSentEvent<String>> pipelineFlux = Flux.<ServerSentEvent<String>>create(sink -> {
                try {
                    long t1 = System.currentTimeMillis();
                    sink.next(sseEvent("context_build_start", "Loading session and retrieving memories"));
                    log.info("[Orchestrator] Stage 1: CONTEXT_BUILD for session={}", sessionId);

                    MemoryQuery memoryQuery = MemoryQuery.builder()
                            .queryText(userMessage)
                            .topK(10)
                            .build();
                    MemoryQueryResult memoryResult = memoryFeignClient.retrieve(memoryQuery);
                    int memoryHits = memoryResult != null ? memoryResult.getTotalHits() : 0;
                    log.info("[Orchestrator] Memory retrieved: {} entries in {}ms",
                            memoryHits, memoryResult != null ? memoryResult.getQueryTimeMs() : 0);
                    sink.next(sseEvent("context_build_complete",
                            "Loaded session, retrieved " + memoryHits + " memory entries"));
                    if (metricsCollector != null) {
                        metricsCollector.recordMemoryRetrieval(
                                memoryResult != null ? memoryResult.getQueryTimeMs() : 0, memoryHits);
                        metricsCollector.recordPipelineStage("CONTEXT_BUILD",
                                System.currentTimeMillis() - t1);
                    }

                    long t2 = System.currentTimeMillis();
                    sink.next(sseEvent("intercept_start", "Running security checks and content filter"));
                    log.info("[Orchestrator] Stage 2: INTERCEPT");

                    if (securityManager != null) {
                        var approvalResult = securityManager.approve(context, "EXECUTE_CHAT");
                        if (!approvalResult.isApproved()) {
                            log.warn("[Orchestrator] Security check denied: {}", approvalResult.getReason());
                            sink.next(sseEvent("intercept_blocked", "Security check denied: " + approvalResult.getReason()));
                            sink.next(sseEvent("done", "{\"status\":\"blocked\"}"));
                            sink.complete();
                            return;
                        }
                    }

                    if (contentFilter != null) {
                        FilterResult filterResult = contentFilter.filter(userMessage, context);
                        if (!filterResult.isPassed()) {
                            log.warn("[Orchestrator] Content filter blocked: {}", filterResult.getReason());
                            sink.next(sseEvent("intercept_blocked", "Content filter blocked: " + filterResult.getReason()));
                            sink.next(sseEvent("done", "{\"status\":\"blocked\"}"));
                            sink.complete();
                            return;
                        }
                    }
                    sink.next(sseEvent("intercept_complete", "Security check and content filter passed"));
                    if (metricsCollector != null) {
                        metricsCollector.recordPipelineStage("INTERCEPT",
                                System.currentTimeMillis() - t2);
                    }

                    long t3 = System.currentTimeMillis();
                    sink.next(sseEvent("plan_start", "Planning task decomposition"));
                    log.info("[Orchestrator] Stage 3: PLAN");

                    PlanRequest planReq = PlanRequest.builder()
                            .sessionId(sessionId)
                            .userIntent(userMessage)
                            .strategy("default")
                            .context(Map.of("sessionId", sessionId, "timestamp", System.currentTimeMillis()))
                            .build();
                    Map<String, Object> planResult = planFeignClient.plan(planReq);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rawNodes = planResult != null && planResult.get("nodes") instanceof List
                            ? (List<Map<String, Object>>) planResult.get("nodes")
                            : Collections.emptyList();
                    for (Map<String, Object> raw : rawNodes) {
                        @SuppressWarnings("unchecked")
                        List<String> tools = raw.containsKey("requiredTools") && raw.get("requiredTools") instanceof List
                                ? (List<String>) raw.get("requiredTools") : Collections.emptyList();
                        @SuppressWarnings("unchecked")
                        List<String> deps = raw.containsKey("dependencies") && raw.get("dependencies") instanceof List
                                ? (List<String>) raw.get("dependencies") : Collections.emptyList();
                        nodes.add(new TaskNode(
                                (String) raw.getOrDefault("nodeId", ""),
                                (String) raw.getOrDefault("type", "EXECUTE"),
                                (String) raw.getOrDefault("description", ""),
                                tools,
                                deps,
                                raw.get("timeoutMs") instanceof Number
                                        ? ((Number) raw.get("timeoutMs")).longValue() : 30000L));
                    }
                    log.info("[Orchestrator] Plan generated: {} task(s)", nodes.size());
                    sink.next(sseEvent("plan_complete", "Planned " + nodes.size() + " task(s)"));

                    for (int i = 0; i < nodes.size(); i++) {
                        TaskNode node = nodes.get(i);
                        sink.next(sseEvent("plan_node",
                                "{\"index\":" + (i + 1) + ",\"nodeId\":\"" + escapeJson(node.getNodeId())
                                        + "\",\"type\":\"" + escapeJson(node.getType())
                                        + "\",\"description\":\"" + escapeJson(node.getDescription()) + "\"}"));
                    }
                    if (metricsCollector != null) metricsCollector.recordPipelineStage("PLAN",
                            System.currentTimeMillis() - t3);

                    long t4 = System.currentTimeMillis();
                    log.info("[Orchestrator] Stage 4: EXECUTE {} task(s)", nodes.size());

                    for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
                        TaskNode node = nodes.get(nodeIndex);
                        sink.next(sseEvent("action_start",
                                "{\"index\":" + (nodeIndex + 1) + ",\"total\":" + nodes.size()
                                        + ",\"nodeId\":\"" + escapeJson(node.getNodeId())
                                        + "\",\"description\":\"" + escapeJson(node.getDescription()) + "\"}"));

                        long toolStart = System.currentTimeMillis();
                        try {
                            ToolExecuteRequest toolReq = ToolExecuteRequest.builder()
                                    .toolName(node.getType())
                                    .args(Map.of("nodeId", node.getNodeId(),
                                            "description", node.getDescription(),
                                            "sessionId", sessionId))
                                    .sessionId(sessionId)
                                    .build();
                            ToolResult result = actionFeignClient.executeTool(toolReq);
                            long toolDuration = System.currentTimeMillis() - toolStart;

                            if (result != null && result.isSuccess()) {
                                successCount.incrementAndGet();
                                String output = result.getOutput() != null ? result.getOutput() : "";
                                toolResults.add(output);
                                sink.next(sseEvent("action_result",
                                        "{\"index\":" + (nodeIndex + 1) + ",\"status\":\"success\",\"output\":\""
                                                + escapeJson(output) + "\",\"durationMs\":" + toolDuration + "}"));
                                if (metricsCollector != null) metricsCollector.recordToolCall(node.getType(), true, toolDuration);
                            } else {
                                failCount.incrementAndGet();
                                String error = result != null ? result.getErrorMessage() : "unknown error";
                                toolResults.add("ERROR: " + error);
                                sink.next(sseEvent("action_result",
                                        "{\"index\":" + (nodeIndex + 1) + ",\"status\":\"failed\",\"error\":\""
                                                + escapeJson(error) + "\",\"durationMs\":" + toolDuration + "}"));
                                if (metricsCollector != null) metricsCollector.recordToolCall(node.getType(), false, toolDuration);
                            }
                        } catch (Exception e) {
                            failCount.incrementAndGet();
                            long toolDuration = System.currentTimeMillis() - toolStart;
                            log.error("[Orchestrator] Tool execution failed: nodeId={}, error={}",
                                    node.getNodeId(), e.getMessage());
                            toolResults.add("ERROR: " + e.getMessage());
                            sink.next(sseEvent("action_result",
                                    "{\"index\":" + (nodeIndex + 1) + ",\"status\":\"error\",\"error\":\""
                                            + escapeJson(e.getMessage()) + "\",\"durationMs\":" + toolDuration + "}"));
                            if (metricsCollector != null) metricsCollector.recordToolCall(node.getType(), false, toolDuration);
                        }
                    }
                    sink.next(sseEvent("action_complete",
                            "{\"total\":" + nodes.size() + ",\"success\":" + successCount.get()
                                    + ",\"failed\":" + failCount.get() + "}"));
                    if (metricsCollector != null) metricsCollector.recordPipelineStage("EXECUTE",
                            System.currentTimeMillis() - t4);

                    long t5 = System.currentTimeMillis();
                    sink.next(sseEvent("reflect_start", "Reflecting on execution results"));
                    log.info("[Orchestrator] Stage 5: REFLECT");

                    String combinedOutput = String.join("\n", toolResults);
                    ReflectRequest reflectReq = ReflectRequest.builder()
                            .sessionId(sessionId)
                            .output(combinedOutput.isEmpty() ? userMessage : combinedOutput)
                            .context("Orchestration pipeline execution - " + nodes.size() + " tasks processed")
                            .build();
                    ReflectionReport r = reflectFeignClient.reflect(reflectReq);
                    reportRef.set(r);
                    double score = r != null ? r.getOverallScore() : 0.0;
                    reflectScoreRef.set(score);
                    log.info("[Orchestrator] Reflection complete: score={}", score);
                    sink.next(sseEvent("reflect_complete",
                            "{\"score\":" + score + ",\"reflectionId\":\""
                                    + (r != null ? r.getReflectionId() : "N/A") + "\"}"));
                    if (metricsCollector != null) metricsCollector.recordPipelineStage("REFLECT",
                            System.currentTimeMillis() - t5);

                    respondStartMs.set(System.currentTimeMillis());
                    pipelineOk.set(true);
                    sink.complete();

                } catch (Exception e) {
                    log.error("[Orchestrator] Pipeline failed: {}", e.getMessage(), e);
                    sink.next(sseEvent("error",
                            "{\"message\":\"" + escapeJson(e.getMessage()) + "\"}"));
                    sink.next(sseEvent("done", "{\"status\":\"error\"}"));
                    sink.complete();
                }
            });

            // ---- Stage 6–7: LLM RESPOND + METRICS ----
            Flux<ServerSentEvent<String>> respondFlux = Flux.defer(() -> {
                if (!pipelineOk.get()) {
                    return Flux.empty();
                }

                int sc = successCount.get();
                int fc = failCount.get();
                ReflectionReport report = reportRef.get();
                double score = reflectScoreRef.get();

                log.info("[Orchestrator] Stage 6: RESPOND");

                ModelAdapter adapter = modelProvider.getConfiguredAdapter();

                Flux<ServerSentEvent<String>> bodyFlux;
                if (adapter != null) {
                    lyjew.com.lyclaw.model.ChatRequest llmRequest = buildLlmRequest(context, toolResults);
                    log.info("[Orchestrator] Calling LLM: provider={}, model={}, messages={}",
                            adapter.getProvider(), adapter.getModel(), llmRequest.getMessageCount());

                    bodyFlux = adapter.chatStream(llmRequest)
                            .handle((line, sink) -> {
                                String text = adapter.extractSsePlainText(line);
                                if (!text.isEmpty()) {
                                    sink.next(sseEvent("message", text));
                                }
                            });
                } else {
                    log.warn("[Orchestrator] No LLM adapter configured, using hardcoded response");
                    String responseText = buildFinalResponse(sc, fc, toolResults, report);
                    bodyFlux = Flux.just(sseEvent("message", responseText));
                }

                return Flux.just(sseEvent("respond_start", "Generating AI response"))
                        .concatWith(bodyFlux)
                        .concatWith(buildTailFlux(
                                context, orchestrationStart, respondStartMs.get(),
                                sc, fc, toolResults, report, score))
                        .onErrorResume(err -> {
                            log.error("[Orchestrator] LLM call failed: {}", err.getMessage());
                            String fallback = buildFinalResponse(sc, fc, toolResults, report);
                            return Flux.just(
                                    sseEvent("message", fallback),
                                    sseEvent("done", "{\"status\":\"completed\",\"fallback\":true}")
                            );
                        });
            });

            return pipelineFlux.concatWith(respondFlux);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<ServerSentEvent<String>> buildTailFlux(ChatContext context, long orchestrationStart, long respondStartMs,
                                        int successCount, int failCount, List<String> toolResults,
                                        ReflectionReport report, double score) {
        String sessionId = context.getRequest().getSessionId();
        int taskCount = toolResults.size();

        // Persist memory
        PerceptionData perception = PerceptionData.builder()
                .role("assistant")
                .content("Orchestration completed | Tasks: " + taskCount
                        + " | Success: " + successCount
                        + " | Failed: " + failCount
                        + " | ReflectScore: " + score)
                .timestamp(System.currentTimeMillis())
                .metadata(Map.of("sessionId", sessionId,
                        "taskCount", taskCount,
                        "successCount", successCount,
                        "failCount", failCount,
                        "orchestrationDurationMs", System.currentTimeMillis() - orchestrationStart))
                .build();
        memoryFeignClient.ingest(perception, sessionId, "default");

        if (metricsCollector != null) {
            metricsCollector.recordPipelineStage("RESPOND",
                    System.currentTimeMillis() - respondStartMs);
        }

        long totalDuration = System.currentTimeMillis() - orchestrationStart;
        log.info("[Orchestrator] Orchestration completed: {} tasks in {}ms",
                taskCount, totalDuration);

        if (metricsCollector != null) {
            metricsCollector.recordPipelineStage("ORCHESTRATION_TOTAL", totalDuration);
            metricsCollector.recordLlmCall("orchestrator", "llm", 0, 0, totalDuration);
            metricsCollector.recordPipelineStage("METRICS", 0);
        }

        return Flux.just(
                sseEvent("respond_complete", "Response generated and memory persisted"),
                sseEvent("metrics",
                        "{\"totalDurationMs\":" + totalDuration
                                + ",\"tasksProcessed\":" + taskCount
                                + ",\"successRate\":"
                                + (taskCount > 0
                                        ? String.format("%.2f", (double) successCount / taskCount)
                                        : "1.0")
                                + ",\"reflectScore\":" + score + "}"),
                sseEvent("done",
                        "{\"status\":\"completed\",\"durationMs\":" + totalDuration + "}")
        );
    }

    private lyjew.com.lyclaw.model.ChatRequest buildLlmRequest(ChatContext context, List<String> toolResults) {
        lyjew.com.lyclaw.model.ChatRequest original = context.getRequest();
        List<Message> messages = new ArrayList<>(original.getMessages());

        if (!toolResults.isEmpty()) {
            String ctx = "Previous tool execution results:\n" + String.join("\n", toolResults);
            messages.add(Message.builder().role("user").content(ctx).build());
        }

        return lyjew.com.lyclaw.model.ChatRequest.builder()
                .messages(messages)
                .stream(true)
                .build();
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

                var modeOpt = collaborationHub.getMode(context.getCollaborationModeId());
                if (modeOpt.isPresent()) {
                    var collabCtx = lyjew.com.lyclaw.agent.collab.CollaborationContext.builder()
                            .collaborationId(collabId)
                            .modeId(context.getCollaborationModeId())
                            .participants(Collections.emptyList())
                            .sharedState(context.getAttributes() != null
                                    ? new HashMap<>(context.getAttributes()) : new HashMap<>())
                            .maxRounds(5)
                            .timeoutMs(300_000L)
                            .build();
                    log.info("[Orchestrator] Delegating to collaboration mode: {}", context.getCollaborationModeId());
                }

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

    private ServerSentEvent<String> sseEvent(String eventType, String payload) {
        return ServerSentEvent.<String>builder().event(eventType).data(payload).build();
    }

    private String buildFinalResponse(int successCount, int failCount,
                                      List<String> toolResults, ReflectionReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Orchestration completed.\n");
        sb.append("Tasks executed: ").append(successCount + failCount)
                .append(" (success: ").append(successCount)
                .append(", failed: ").append(failCount).append(")\n");

        if (report != null) {
            sb.append("Reflection score: ").append(String.format("%.2f", report.getOverallScore())).append("\n");
            if (report.getQuality() != null) {
                sb.append("Quality - accuracy: ").append(String.format("%.2f", report.getQuality().getAccuracy()))
                        .append(", completeness: ").append(String.format("%.2f", report.getQuality().getCompleteness()))
                        .append(", safety: ").append(String.format("%.2f", report.getQuality().getSafety()))
                        .append("\n");
            }
        }

        if (!toolResults.isEmpty()) {
            sb.append("\nResults summary:\n");
            for (int i = 0; i < Math.min(toolResults.size(), 5); i++) {
                String result = toolResults.get(i);
                sb.append("  [").append(i + 1).append("] ")
                        .append(result.length() > 200 ? result.substring(0, 200) + "..." : result)
                        .append("\n");
            }
            if (toolResults.size() > 5) {
                sb.append("  ... and ").append(toolResults.size() - 5).append(" more results\n");
            }
        }

        return sb.toString();
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
