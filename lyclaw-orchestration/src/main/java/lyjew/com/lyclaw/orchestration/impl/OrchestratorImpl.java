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
import lyjew.com.lyclaw.infra.metrics.MetricsCollector;
import lyjew.com.lyclaw.memory.MemoryQuery;
import lyjew.com.lyclaw.memory.MemoryQueryResult;
import lyjew.com.lyclaw.memory.PerceptionData;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.orchestration.AgentEvent;
import lyjew.com.lyclaw.orchestration.OrchestrationContext;
import lyjew.com.lyclaw.orchestration.Orchestrator;
import lyjew.com.lyclaw.reflect.ReflectRequest;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import lyjew.com.lyclaw.security.SecurityManager;
import lyjew.com.lyclaw.task.PlanRequest;
import lyjew.com.lyclaw.task.TaskNode;
import lyjew.com.lyclaw.task.TaskPlan;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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

    private final ConcurrentHashMap<String, Boolean> cancellationFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> progressTracker = new ConcurrentHashMap<>();

    public OrchestratorImpl(PlanFeignClient planFeignClient,
                            ActionFeignClient actionFeignClient,
                            ReflectFeignClient reflectFeignClient,
                            MemoryFeignClient memoryFeignClient,
                            SecurityManager securityManager,
                            ContentFilter contentFilter,
                            MetricsCollector metricsCollector,
                            CollaborationHub collaborationHub) {
        this.planFeignClient = planFeignClient;
        this.actionFeignClient = actionFeignClient;
        this.reflectFeignClient = reflectFeignClient;
        this.memoryFeignClient = memoryFeignClient;
        this.securityManager = securityManager;
        this.contentFilter = contentFilter;
        this.metricsCollector = metricsCollector;
        this.collaborationHub = collaborationHub;
    }

    @Override
    public Flux<String> execute(ChatContext context) {
        return Flux.defer(() -> {
            long orchestrationStart = System.currentTimeMillis();
            ChatRequest request = context.getRequest();
            String sessionId = request.getSessionId();
            String userMessage = request.getLastUserMessage();

            return Flux.<String>create(sink -> {
            try {
                long t1 = System.currentTimeMillis();
                sink.next(formatSSE("context_build_start", "Loading session and retrieving memories"));
                log.info("[Orchestrator] Stage 1: CONTEXT_BUILD for session={}", sessionId);

                MemoryQuery memoryQuery = MemoryQuery.builder()
                        .queryText(userMessage)
                        .topK(10)
                        .build();
                MemoryQueryResult memoryResult = memoryFeignClient.retrieve(memoryQuery);
                int memoryHits = memoryResult != null ? memoryResult.getTotalHits() : 0;
                log.info("[Orchestrator] Memory retrieved: {} entries in {}ms",
                        memoryHits, memoryResult != null ? memoryResult.getQueryTimeMs() : 0);
                sink.next(formatSSE("context_build_complete",
                        "Loaded session, retrieved " + memoryHits + " memory entries"));
                if (metricsCollector != null) {
                    metricsCollector.recordMemoryRetrieval(
                            memoryResult != null ? memoryResult.getQueryTimeMs() : 0, memoryHits);
                    metricsCollector.recordPipelineStage("CONTEXT_BUILD",
                            System.currentTimeMillis() - t1);
                }

                long t2 = System.currentTimeMillis();
                sink.next(formatSSE("intercept_start", "Running security checks and content filter"));
                log.info("[Orchestrator] Stage 2: INTERCEPT");

                if (securityManager != null) {
                    var approvalResult = securityManager.approve(context, "EXECUTE_CHAT");
                    if (!approvalResult.isApproved()) {
                        log.warn("[Orchestrator] Security check denied: {}", approvalResult.getReason());
                        sink.next(formatSSE("intercept_blocked", "Security check denied: " + approvalResult.getReason()));
                        sink.next(formatSSE("done", "orchestration_blocked"));
                        sink.complete();
                        return;
                    }
                }

                if (contentFilter != null) {
                    FilterResult filterResult = contentFilter.filter(userMessage, context);
                    if (!filterResult.isPassed()) {
                        log.warn("[Orchestrator] Content filter blocked: {}", filterResult.getReason());
                        sink.next(formatSSE("intercept_blocked", "Content filter blocked: " + filterResult.getReason()));
                        sink.next(formatSSE("done", "orchestration_blocked"));
                        sink.complete();
                        return;
                    }
                }
                sink.next(formatSSE("intercept_complete", "Security check and content filter passed"));
                if (metricsCollector != null) {
                    metricsCollector.recordPipelineStage("INTERCEPT",
                            System.currentTimeMillis() - t2);
                }

                long t3 = System.currentTimeMillis();
                sink.next(formatSSE("plan_start", "Planning task decomposition"));
                log.info("[Orchestrator] Stage 3: PLAN");

                PlanRequest planReq = PlanRequest.builder()
                        .sessionId(sessionId)
                        .userIntent(userMessage)
                        .strategy("default")
                        .context(Map.of("sessionId", sessionId, "timestamp", System.currentTimeMillis()))
                        .build();
                TaskPlan plan = planFeignClient.plan(planReq);
                List<TaskNode> nodes = plan != null ? plan.getNodes() : Collections.emptyList();
                log.info("[Orchestrator] Plan generated: {} task(s)", nodes.size());
                sink.next(formatSSE("plan_complete", "Planned " + nodes.size() + " task(s)"));

                for (int i = 0; i < nodes.size(); i++) {
                    TaskNode node = nodes.get(i);
                    sink.next(formatSSE("plan_node",
                            "{\"index\":" + (i + 1) + ",\"nodeId\":\"" + escapeJson(node.getNodeId())
                                    + "\",\"type\":\"" + escapeJson(node.getType())
                                    + "\",\"description\":\"" + escapeJson(node.getDescription()) + "\"}"));
                }
                if (metricsCollector != null) metricsCollector.recordPipelineStage("PLAN",
                        System.currentTimeMillis() - t3);

                long t4 = System.currentTimeMillis();
                log.info("[Orchestrator] Stage 4: EXECUTE {} task(s)", nodes.size());

                List<String> toolResults = new ArrayList<>();
                AtomicInteger successCount = new AtomicInteger(0);
                AtomicInteger failCount = new AtomicInteger(0);

                for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
                    TaskNode node = nodes.get(nodeIndex);
                    sink.next(formatSSE("action_start",
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
                            sink.next(formatSSE("action_result",
                                    "{\"index\":" + (nodeIndex + 1) + ",\"status\":\"success\",\"output\":\""
                                            + escapeJson(output) + "\",\"durationMs\":" + toolDuration + "}"));
                            if (metricsCollector != null) metricsCollector.recordToolCall(node.getType(), true, toolDuration);
                        } else {
                            failCount.incrementAndGet();
                            String error = result != null ? result.getErrorMessage() : "unknown error";
                            toolResults.add("ERROR: " + error);
                            sink.next(formatSSE("action_result",
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
                        sink.next(formatSSE("action_result",
                                "{\"index\":" + (nodeIndex + 1) + ",\"status\":\"error\",\"error\":\""
                                        + escapeJson(e.getMessage()) + "\",\"durationMs\":" + toolDuration + "}"));
                        if (metricsCollector != null) metricsCollector.recordToolCall(node.getType(), false, toolDuration);
                    }
                }
                sink.next(formatSSE("action_complete",
                        "{\"total\":" + nodes.size() + ",\"success\":" + successCount.get()
                                + ",\"failed\":" + failCount.get() + "}"));
                if (metricsCollector != null) metricsCollector.recordPipelineStage("EXECUTE",
                        System.currentTimeMillis() - t4);

                long t5 = System.currentTimeMillis();
                sink.next(formatSSE("reflect_start", "Reflecting on execution results"));
                log.info("[Orchestrator] Stage 5: REFLECT");

                String combinedOutput = String.join("\n", toolResults);
                ReflectRequest reflectReq = ReflectRequest.builder()
                        .sessionId(sessionId)
                        .output(combinedOutput.isEmpty() ? userMessage : combinedOutput)
                        .context("Orchestration pipeline execution - " + nodes.size() + " tasks processed")
                        .build();
                ReflectionReport report = reflectFeignClient.reflect(reflectReq);
                double score = report != null ? report.getOverallScore() : 0.0;
                log.info("[Orchestrator] Reflection complete: score={}", score);
                sink.next(formatSSE("reflect_complete",
                        "{\"score\":" + score + ",\"reflectionId\":\""
                                + (report != null ? report.getReflectionId() : "N/A") + "\"}"));
                if (metricsCollector != null) metricsCollector.recordPipelineStage("REFLECT",
                        System.currentTimeMillis() - t5);

                long t6 = System.currentTimeMillis();
                sink.next(formatSSE("respond_start", "Building response and persisting memories"));
                log.info("[Orchestrator] Stage 6: RESPOND");

                String responseText = buildFinalResponse(successCount.get(), failCount.get(), toolResults, report);

                PerceptionData perception = PerceptionData.builder()
                        .role("assistant")
                        .content("Orchestration pipeline completed for session: " + sessionId
                                + " | Tasks: " + nodes.size()
                                + " | Success: " + successCount.get()
                                + " | Failed: " + failCount.get()
                                + " | ReflectScore: " + score)
                        .timestamp(System.currentTimeMillis())
                        .metadata(Map.of("sessionId", sessionId,
                                "taskCount", nodes.size(),
                                "successCount", successCount.get(),
                                "failCount", failCount.get(),
                                "orchestrationDurationMs", System.currentTimeMillis() - orchestrationStart))
                        .build();
                memoryFeignClient.ingest(perception, sessionId, "default");
                sink.next(formatSSE("respond_complete", "Response built and memory persisted"));

                sink.next(formatSSE("message", escapeJson(responseText)));
                if (metricsCollector != null) metricsCollector.recordPipelineStage("RESPOND",
                        System.currentTimeMillis() - t6);

                long t7 = System.currentTimeMillis();
                log.info("[Orchestrator] Stage 7: METRICS");
                long totalDuration = System.currentTimeMillis() - orchestrationStart;
                if (metricsCollector != null) {
                    metricsCollector.recordPipelineStage("ORCHESTRATION_TOTAL", totalDuration);
                    metricsCollector.recordLlmCall("orchestrator", "scheduler", 0, responseText.length(), totalDuration);
                }
                sink.next(formatSSE("metrics",
                        "{\"totalDurationMs\":" + totalDuration
                                + ",\"tasksProcessed\":" + nodes.size()
                                + ",\"successRate\":"
                                + (nodes.size() > 0
                                        ? String.format("%.2f", (double) successCount.get() / nodes.size())
                                        : "1.0")
                                + ",\"reflectScore\":" + score + "}"));
                if (metricsCollector != null) {
                    metricsCollector.recordPipelineStage("METRICS",
                            System.currentTimeMillis() - t7);
                }

                sink.next(formatSSE("done",
                        "{\"status\":\"completed\",\"durationMs\":" + totalDuration + "}"));
                log.info("[Orchestrator] Orchestration completed: {} tasks in {}ms",
                        nodes.size(), totalDuration);
                sink.complete();

            } catch (Exception e) {
                log.error("[Orchestrator] Orchestration failed: {}", e.getMessage(), e);
                long errorDuration = System.currentTimeMillis() - orchestrationStart;
                sink.next(formatSSE("error",
                        "{\"message\":\"" + escapeJson(e.getMessage()) + "\",\"durationMs\":" + errorDuration + "}"));
                sink.next(formatSSE("done",
                        "{\"status\":\"error\",\"durationMs\":" + errorDuration + "}"));
                sink.complete();
            }
        });
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

    private String formatSSE(String eventType, String payload) {
        return "event: " + eventType + "\ndata: " + payload + "\n\n";
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
