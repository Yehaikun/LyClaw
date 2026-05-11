package lyjew.com.lyclaw.orchestration.stage;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.action.ToolExecuteRequest;
import lyjew.com.lyclaw.action.tool.ToolResult;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.feign.ActionFeignClient;
import lyjew.com.lyclaw.feign.PlanFeignClient;
import lyjew.com.lyclaw.infra.metrics.MetricsCollector;
import lyjew.com.lyclaw.pipeline.PipelineContext;
import lyjew.com.lyclaw.task.PlanRequest;
import lyjew.com.lyclaw.task.TaskNode;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;

@Slf4j
@Component
public class PlanExecutionStage extends PipelineStageBase {

    private final PlanFeignClient planFeignClient;
    private final ActionFeignClient actionFeignClient;
    private final MetricsCollector metricsCollector;

    public PlanExecutionStage(PlanFeignClient planFeignClient,
                               ActionFeignClient actionFeignClient,
                               @org.springframework.lang.Nullable MetricsCollector metricsCollector) {
        this.planFeignClient = planFeignClient;
        this.actionFeignClient = actionFeignClient;
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

                // ---- PLAN ----
                pipelineCtx.getCurrentStage().set("PLAN");
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
                    pipelineCtx.addNode(new TaskNode(
                            (String) raw.getOrDefault("nodeId", ""),
                            (String) raw.getOrDefault("type", "EXECUTE"),
                            (String) raw.getOrDefault("description", ""),
                            tools,
                            deps,
                            raw.get("timeoutMs") instanceof Number
                                    ? ((Number) raw.get("timeoutMs")).longValue() : 30000L));
                }
                log.info(logJson("INFO", "plan_result", "PLAN", traceId,
                        "Plan generated: " + pipelineCtx.getNodes().size() + " task(s)", null));
                sink.next(sseEvent("plan_complete",
                        "Planned " + pipelineCtx.getNodes().size() + " task(s)"));

                List<TaskNode> nodes = pipelineCtx.getNodes();
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
                if (metricsCollector != null) {
                    metricsCollector.recordPipelineStage("PLAN", stage3Duration);
                }

                // ---- EXECUTE ----
                pipelineCtx.getCurrentStage().set("EXECUTE");
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
                            pipelineCtx.getSuccessCount().incrementAndGet();
                            String output = result.getOutput() != null ? result.getOutput() : "";
                            pipelineCtx.addToolResult(output);
                            log.info(logJson("INFO", "feign_call", "EXECUTE", traceId,
                                    "actionFeignClient.executeTool success: " + node.getNodeId(),
                                    actionCallDuration));
                            sink.next(sseEvent("action_result",
                                    "{\"index\":" + (nodeIndex + 1) + ",\"status\":\"success\",\"output\":\""
                                            + escapeJson(output) + "\",\"durationMs\":" + toolDuration + "}"));
                            if (metricsCollector != null) {
                                metricsCollector.recordToolCall(node.getType(), true, toolDuration);
                            }
                        } else {
                            pipelineCtx.getFailCount().incrementAndGet();
                            String error = result != null ? result.getErrorMessage() : "unknown error";
                            pipelineCtx.addToolResult("ERROR: " + error);
                            log.warn(logJson("WARN", "feign_call", "EXECUTE", traceId,
                                    "actionFeignClient.executeTool failed: " + node.getNodeId()
                                            + " error=" + error,
                                    actionCallDuration));
                            sink.next(sseEvent("action_result",
                                    "{\"index\":" + (nodeIndex + 1) + ",\"status\":\"failed\",\"error\":\""
                                            + escapeJson(error) + "\",\"durationMs\":" + toolDuration + "}"));
                            if (metricsCollector != null) {
                                metricsCollector.recordToolCall(node.getType(), false, toolDuration);
                            }
                        }
                    } catch (Exception e) {
                        pipelineCtx.getFailCount().incrementAndGet();
                        long toolDuration = System.currentTimeMillis() - toolStart;
                        log.error(logJson("ERROR", "feign_call", "EXECUTE", traceId,
                                "actionFeignClient.executeTool exception: nodeId="
                                        + node.getNodeId() + " error=" + e.getMessage(),
                                toolDuration));
                        pipelineCtx.addToolResult("ERROR: " + e.getMessage());
                        sink.next(sseEvent("action_result",
                                "{\"index\":" + (nodeIndex + 1) + ",\"status\":\"error\",\"error\":\""
                                        + escapeJson(e.getMessage()) + "\",\"durationMs\":" + toolDuration + "}"));
                        if (metricsCollector != null) {
                            metricsCollector.recordToolCall(node.getType(), false, toolDuration);
                        }
                    }
                }
                sink.next(sseEvent("action_complete",
                        "{\"total\":" + nodes.size() + ",\"success\":" + pipelineCtx.getSuccessCount().get()
                                + ",\"failed\":" + pipelineCtx.getFailCount().get() + "}"));
                long stage4Duration = System.currentTimeMillis() - t4;
                context.getTracing().endStage("EXECUTE");
                log.info(logJson("INFO", "stage_complete", "EXECUTE", traceId,
                        "Execution complete: " + pipelineCtx.getSuccessCount().get() + " success, "
                                + pipelineCtx.getFailCount().get() + " failed",
                        stage4Duration));
                if (metricsCollector != null) {
                    metricsCollector.recordPipelineStage("EXECUTE", stage4Duration);
                }

                sink.complete();
            } catch (Exception e) {
                log.error(logJson("ERROR", "stage_error", "PLAN_EXECUTE", traceId,
                        "Plan/execute failed, continuing degraded: " + e.getMessage(), null));
                pipelineCtx.getCurrentStage().set("EXECUTE");
                sink.next(sseEvent("plan_complete", "Plan execution degraded (remote services unavailable)"));
                sink.complete();
            }
        });
    }

    @Override
    public int getOrder() { return 2; }

    @Override
    public String getStageName() { return "PlanExecution"; }
}
