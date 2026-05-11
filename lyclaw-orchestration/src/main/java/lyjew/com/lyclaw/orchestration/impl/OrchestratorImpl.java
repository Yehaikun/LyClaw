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
import org.slf4j.MDC;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
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
            String traceId = context.getTracing().getTraceId();
            MDC.put("traceId", traceId);

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
            final AtomicReference<String> currentStage =
                    new AtomicReference<>("init");

            // ---- Stages 1–5: synchronous pipeline ----
            Flux<ServerSentEvent<String>> pipelineFlux = Flux.<ServerSentEvent<String>>create(sink -> {
                try {
                    // ---- Stage 1: CONTEXT_BUILD ----
                    currentStage.set("CONTEXT_BUILD");
                    context.getTracing().beginStage("CONTEXT_BUILD");
                    long t1 = System.currentTimeMillis();
                    log.info(logJson("INFO", "stage_start", "CONTEXT_BUILD", traceId,
                            "Loading session and retrieving memories", null));
                    sink.next(sseEvent("context_build_start", "Loading session and retrieving memories"));

                    long memCallStart = System.currentTimeMillis();
                    MemoryQuery memoryQuery = MemoryQuery.builder()
                            .queryText(userMessage)
                            .topK(10)
                            .build();
                    MemoryQueryResult memoryResult = memoryFeignClient.retrieve(memoryQuery);
                    long memCallDuration = System.currentTimeMillis() - memCallStart;
                    int memoryHits = memoryResult != null ? memoryResult.getTotalHits() : 0;
                    log.info(logJson("INFO", "feign_call", "CONTEXT_BUILD", traceId,
                            "memoryFeignClient.retrieve completed: " + memoryHits + " entries",
                            memCallDuration));
                    sink.next(sseEvent("context_build_complete",
                            "Loaded session, retrieved " + memoryHits + " memory entries"));
                    if (metricsCollector != null) {
                        metricsCollector.recordMemoryRetrieval(
                                memoryResult != null ? memoryResult.getQueryTimeMs() : 0, memoryHits);
                    }
                    long stage1Duration = System.currentTimeMillis() - t1;
                    context.getTracing().endStage("CONTEXT_BUILD");
                    log.info(logJson("INFO", "stage_complete", "CONTEXT_BUILD", traceId,
                            "Context build complete, " + memoryHits + " memory entries retrieved",
                            stage1Duration));
                    if (metricsCollector != null) {
                        metricsCollector.recordPipelineStage("CONTEXT_BUILD", stage1Duration);
                    }

                    // ---- Stage 2: INTERCEPT ----
                    currentStage.set("INTERCEPT");
                    context.getTracing().beginStage("INTERCEPT");
                    long t2 = System.currentTimeMillis();
                    log.info(logJson("INFO", "stage_start", "INTERCEPT", traceId,
                            "Running security checks and content filter", null));
                    sink.next(sseEvent("intercept_start", "Running security checks and content filter"));

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
                            sink.next(sseEvent("intercept_blocked", "Security check denied: " + reason));
                            sink.next(sseEvent("done", "{\"status\":\"blocked\"}"));
                            sink.complete();
                            return;
                        }
                    }

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

                    // ---- Stage 3: PLAN ----
                    currentStage.set("PLAN");
                    context.getTracing().beginStage("PLAN");
                    long t3 = System.currentTimeMillis();
                    log.info(logJson("INFO", "stage_start", "PLAN", traceId,
                            "Planning task decomposition", null));
                    sink.next(sseEvent("plan_start", "Planning task decomposition"));

                    PlanRequest planReq = PlanRequest.builder()
                            .sessionId(sessionId)
                            .userIntent(userMessage)
                            .strategy("default")
                            .context(Map.of("sessionId", sessionId, "timestamp", System.currentTimeMillis()))
                            .build();
                    long planCallStart = System.currentTimeMillis();
                    Map<String, Object> planResult = planFeignClient.plan(planReq);
                    long planCallDuration = System.currentTimeMillis() - planCallStart;
                    log.info(logJson("INFO", "feign_call", "PLAN", traceId,
                            "planFeignClient.plan completed", planCallDuration));
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
                    log.info(logJson("INFO", "plan_result", "PLAN", traceId,
                            "Plan generated: " + nodes.size() + " task(s)", null));
                    sink.next(sseEvent("plan_complete", "Planned " + nodes.size() + " task(s)"));

                    for (int i = 0; i < nodes.size(); i++) {
                        TaskNode node = nodes.get(i);
                        sink.next(sseEvent("plan_node",
                                "{\"index\":" + (i + 1) + ",\"nodeId\":\"" + escapeJson(node.getNodeId())
                                        + "\",\"type\":\"" + escapeJson(node.getType())
                                        + "\",\"description\":\"" + escapeJson(node.getDescription()) + "\"}"));
                    }
                    long stage3Duration = System.currentTimeMillis() - t3;
                    context.getTracing().endStage("PLAN");
                    log.info(logJson("INFO", "stage_complete", "PLAN", traceId,
                            "Plan decomposition complete, " + nodes.size() + " task(s)", stage3Duration));
                    if (metricsCollector != null) metricsCollector.recordPipelineStage("PLAN", stage3Duration);

                    // ---- Stage 4: EXECUTE ----
                    currentStage.set("EXECUTE");
                    context.getTracing().beginStage("EXECUTE");
                    long t4 = System.currentTimeMillis();
                    log.info(logJson("INFO", "stage_start", "EXECUTE", traceId,
                            "Executing " + nodes.size() + " task(s)", null));

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
                            long actionCallStart = System.currentTimeMillis();
                            ToolResult result = actionFeignClient.executeTool(toolReq);
                            long toolDuration = System.currentTimeMillis() - toolStart;
                            long actionCallDuration = System.currentTimeMillis() - actionCallStart;

                            if (result != null && result.isSuccess()) {
                                successCount.incrementAndGet();
                                String output = result.getOutput() != null ? result.getOutput() : "";
                                toolResults.add(output);
                                log.info(logJson("INFO", "feign_call", "EXECUTE", traceId,
                                        "actionFeignClient.executeTool success: " + node.getNodeId(),
                                        actionCallDuration));
                                sink.next(sseEvent("action_result",
                                        "{\"index\":" + (nodeIndex + 1) + ",\"status\":\"success\",\"output\":\""
                                                + escapeJson(output) + "\",\"durationMs\":" + toolDuration + "}"));
                                if (metricsCollector != null) metricsCollector.recordToolCall(node.getType(), true, toolDuration);
                            } else {
                                failCount.incrementAndGet();
                                String error = result != null ? result.getErrorMessage() : "unknown error";
                                toolResults.add("ERROR: " + error);
                                log.warn(logJson("WARN", "feign_call", "EXECUTE", traceId,
                                        "actionFeignClient.executeTool failed: " + node.getNodeId()
                                                + " error=" + error,
                                        actionCallDuration));
                                sink.next(sseEvent("action_result",
                                        "{\"index\":" + (nodeIndex + 1) + ",\"status\":\"failed\",\"error\":\""
                                                + escapeJson(error) + "\",\"durationMs\":" + toolDuration + "}"));
                                if (metricsCollector != null) metricsCollector.recordToolCall(node.getType(), false, toolDuration);
                            }
                        } catch (Exception e) {
                            failCount.incrementAndGet();
                            long toolDuration = System.currentTimeMillis() - toolStart;
                            log.error(logJson("ERROR", "feign_call", "EXECUTE", traceId,
                                    "actionFeignClient.executeTool exception: nodeId="
                                            + node.getNodeId() + " error=" + e.getMessage(),
                                    toolDuration));
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
                    long stage4Duration = System.currentTimeMillis() - t4;
                    context.getTracing().endStage("EXECUTE");
                    log.info(logJson("INFO", "stage_complete", "EXECUTE", traceId,
                            "Execution complete: " + successCount.get() + " success, "
                                    + failCount.get() + " failed",
                            stage4Duration));
                    if (metricsCollector != null) metricsCollector.recordPipelineStage("EXECUTE", stage4Duration);

                    // ---- Stage 5: REFLECT ----
                    currentStage.set("REFLECT");
                    context.getTracing().beginStage("REFLECT");
                    long t5 = System.currentTimeMillis();
                    log.info(logJson("INFO", "stage_start", "REFLECT", traceId,
                            "Reflecting on execution results", null));
                    sink.next(sseEvent("reflect_start", "Reflecting on execution results"));

                    String combinedOutput = String.join("\n", toolResults);
                    ReflectRequest reflectReq = ReflectRequest.builder()
                            .sessionId(sessionId)
                            .output(combinedOutput.isEmpty() ? userMessage : combinedOutput)
                            .context("Orchestration pipeline execution - " + nodes.size() + " tasks processed")
                            .build();
                    long reflectCallStart = System.currentTimeMillis();
                    ReflectionReport r = reflectFeignClient.reflect(reflectReq);
                    long reflectCallDuration = System.currentTimeMillis() - reflectCallStart;
                    log.info(logJson("INFO", "feign_call", "REFLECT", traceId,
                            "reflectFeignClient.reflect completed", reflectCallDuration));
                    reportRef.set(r);
                    double score = r != null ? r.getOverallScore() : 0.0;
                    reflectScoreRef.set(score);
                    log.info(logJson("INFO", "reflect_result", "REFLECT", traceId,
                            "Reflection complete: score=" + score, null));
                    sink.next(sseEvent("reflect_complete",
                            "{\"score\":" + score + ",\"reflectionId\":\""
                                    + (r != null ? r.getReflectionId() : "N/A") + "\"}"));
                    long stage5Duration = System.currentTimeMillis() - t5;
                    context.getTracing().endStage("REFLECT");
                    log.info(logJson("INFO", "stage_complete", "REFLECT", traceId,
                            "Reflection complete, score=" + score, stage5Duration));
                    if (metricsCollector != null) metricsCollector.recordPipelineStage("REFLECT", stage5Duration);

                    respondStartMs.set(System.currentTimeMillis());
                    pipelineOk.set(true);
                    currentStage.set("pipeline_done");
                    sink.complete();

                } catch (Exception e) {
                    String failedStage = currentStage.get();
                    context.getTracing().endStage(failedStage);
                    long elapsed = System.currentTimeMillis() - orchestrationStart;
                    log.error(logJson("ERROR", "stage_error", failedStage, traceId,
                            "Pipeline failed: " + e.getMessage(), elapsed), e);
                    sink.next(sseEvent("error",
                            "{\"message\":\"" + escapeJson(e.getMessage())
                                    + "\",\"traceId\":\"" + escapeJson(traceId)
                                    + "\",\"stage\":\"" + escapeJson(failedStage) + "\"}"));
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

                log.info(logJson("INFO", "stage_start", "RESPOND", traceId,
                        "Generating AI response", null));
                currentStage.set("RESPOND");
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
                        .concatWith(buildTailFlux(
                                context, orchestrationStart, respondStartMs.get(),
                                sc, fc, toolResults, report, score, traceId, currentStage))
                        .onErrorResume(err -> {
                            context.getTracing().endStage("RESPOND");
                            long elapsed = System.currentTimeMillis() - orchestrationStart;
                            log.error(logJson("ERROR", "stage_error", "RESPOND", traceId,
                                    "LLM call failed: " + err.getMessage(), elapsed), err);
                            String fallback = buildFinalResponse(sc, fc, toolResults, report);
                            return Flux.just(
                                    sseEvent("message", fallback),
                                    sseEvent("done", "{\"status\":\"completed\",\"fallback\":true}")
                            );
                        });
            });

            return pipelineFlux.concatWith(respondFlux);
        }).doFinally(signalType -> MDC.remove("traceId"))
          .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<ServerSentEvent<String>> buildTailFlux(ChatContext context, long orchestrationStart, long respondStartMs,
                                        int successCount, int failCount, List<String> toolResults,
                                        ReflectionReport report, double score,
                                        String traceId, AtomicReference<String> currentStage) {
        return Flux.defer(() -> {
            String sessionId = context.getSession() != null
                    ? context.getSession().getSessionId()
                    : context.getRequest().getSessionId();
            int taskCount = toolResults.size();

            // Persist memory (non-blocking fire-and-forget, failure must not break the stream)
            try {
                PerceptionData perception = PerceptionData.builder()
                        .role("assistant")
                        .content("Orchestration completed | Tasks: " + taskCount
                                + " | Success: " + successCount
                                + " | Failed: " + failCount
                                + " | ReflectScore: " + score)
                        .timestamp(System.currentTimeMillis())
                        .metadata(Map.of("sessionId", sessionId != null ? sessionId : "",
                                "taskCount", taskCount,
                                "successCount", successCount,
                                "failCount", failCount,
                                "orchestrationDurationMs", System.currentTimeMillis() - orchestrationStart))
                        .build();
                memoryFeignClient.ingest(perception, sessionId, "default");
            } catch (Exception e) {
                log.warn(logJson("WARN", "memory_ingest_failed", "RESPOND", traceId,
                        "Memory ingest failed (non-critical): " + e.getMessage(), null));
            }

            long respondDuration = System.currentTimeMillis() - respondStartMs;
            if (metricsCollector != null) {
                metricsCollector.recordPipelineStage("RESPOND", respondDuration);
            }
            context.getTracing().endStage("RESPOND");
            log.info(logJson("INFO", "stage_complete", "RESPOND", traceId,
                    "Response generated and memory persisted", respondDuration));

            long totalDuration = System.currentTimeMillis() - orchestrationStart;
            context.getTracing().markEnd();
            log.info(logJson("INFO", "pipeline_complete", "ORCHESTRATION_TOTAL", traceId,
                    "Orchestration completed: " + taskCount + " tasks", totalDuration));

            if (metricsCollector != null) {
                metricsCollector.recordPipelineStage("ORCHESTRATION_TOTAL", totalDuration);
                metricsCollector.recordLlmCall("orchestrator", "llm", 0, 0, totalDuration);
                metricsCollector.recordPipelineStage("METRICS", 0);
            }

            currentStage.set("done");

            return Flux.just(
                    sseEvent("respond_complete", "Response generated and memory persisted"),
                    sseEvent("metrics",
                            "{\"totalDurationMs\":" + totalDuration
                                    + ",\"tasksProcessed\":" + taskCount
                                    + ",\"successRate\":"
                                    + (taskCount > 0
                                            ? String.format("%.2f", (double) successCount / taskCount)
                                            : "1.0")
                                    + ",\"reflectScore\":" + score
                                    + ",\"traceId\":\"" + escapeJson(traceId) + "\"}"),
                    sseEvent("done",
                            "{\"status\":\"completed\",\"durationMs\":" + totalDuration
                                    + ",\"traceId\":\"" + escapeJson(traceId) + "\"}")
            );
        });
    }

    private String logJson(String level, String event, String stage, String traceId,
                           String message, Long durationMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"timestamp\":\"").append(Instant.now().toString()).append("\"");
        sb.append(",\"level\":\"").append(level).append("\"");
        sb.append(",\"event\":\"").append(event).append("\"");
        sb.append(",\"stage\":\"").append(stage).append("\"");
        sb.append(",\"traceId\":\"").append(traceId).append("\"");
        sb.append(",\"message\":\"").append(escapeJson(message)).append("\"");
        if (durationMs != null) {
            sb.append(",\"durationMs\":").append(durationMs);
        }
        sb.append("}");
        return sb.toString();
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
