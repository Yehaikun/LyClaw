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
import lyjew.com.lyclaw.framework.annotation.PipelineStage;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * 计划执行阶段，属于核心（CORE）组，在 SecurityCheckStage 之后执行。
 *
 * <p>职责：这是编排引擎的核心阶段，包含两个子阶段：
 * <ol>
 *   <li><b>PLAN（任务规划）</b>：调用远程规划服务将用户意图分解为多个可执行的任务节点（TaskNode）。</li>
 *   <li><b>EXECUTE（任务执行）</b>：按顺序逐个执行已规划的任务节点，通过 Feign 调用远程执行器。</li>
 * </ol>
 *
 * <p>每个任务节点包含节点ID、类型、描述、所需工具、依赖关系和超时时间等属性。
 * 执行结果（成功/失败/异常）通过 SSE 事件实时推送给前端，并记录到 PipelineContext 中供后续阶段使用。
 * 如果整体阶段发生异常，会降级继续（degraded mode），允许后续 RespondStage 给出降级响应。
 *
 * <p>执行顺序：第 2 位（getOrder 返回 2）。
 */
@Slf4j
@PipelineStage(name = "PlanExecution", after = SecurityCheckStage.class, group = "CORE")
public class PlanExecutionStage extends PipelineStageBase {

    /** 规划服务 Feign 客户端，用于将用户意图分解为任务 */
    private final PlanFeignClient planFeignClient;
    /** 执行服务 Feign 客户端，用于远程执行工具调用 */
    private final ActionFeignClient actionFeignClient;
    /** 指标采集器，用于记录工具调用和阶段耗时 */
    private final MetricsCollector metricsCollector;

    /**
     * 构造计划执行阶段。
     *
     * @param planFeignClient   规划服务远程调用客户端
     * @param actionFeignClient 执行服务远程调用客户端
     * @param metricsCollector  指标采集器，可为 null
     */
    public PlanExecutionStage(PlanFeignClient planFeignClient,
                               ActionFeignClient actionFeignClient,
                               @org.springframework.lang.Nullable MetricsCollector metricsCollector) {
        this.planFeignClient = planFeignClient;
        this.actionFeignClient = actionFeignClient;
        this.metricsCollector = metricsCollector;
    }

    /**
     * 执行计划与执行双阶段流程。
     *
     * <p>子阶段 PLAN：
     * <ol>
     *   <li>构建 PlanRequest，将用户意图发送给远程规划服务。</li>
     *   <li>解析返回的原始节点数据，转换为 TaskNode 列表并存入 PipelineContext。</li>
     *   <li>逐个发送 plan_node SSE 事件通知前端。</li>
     * </ol>
     *
     * <p>子阶段 EXECUTE：
     * <ol>
     *   <li>遍历所有 TaskNode，逐个构建 ToolExecuteRequest 并调用远程执行器。</li>
     *   <li>成功时累加 successCount，失败/异常时累加 failCount。</li>
     *   <li>每次执行结果通过 action_result SSE 事件推送给前端。</li>
     *   <li>全部完成后发送 action_complete 汇总事件。</li>
     * </ol>
     *
     * @param context     当前聊天上下文，包含 sessionId、用户消息和追踪信息
     * @param pipelineCtx 流水线上下文，用于存储任务节点和执行结果
     * @return SSE 事件流，实时推送规划进度和每个任务的执行结果
     */
    @Override
    public Flux<ServerSentEvent<String>> execute(ChatContext context, PipelineContext pipelineCtx) {
        // 流水线已被上游终止，跳过本阶段
        if (pipelineCtx.isTerminated()) return Flux.empty();

        return Flux.create(sink -> {
            String traceId = context.getTracing().getTraceId();
            try {
                String sessionId = context.getRequest().getSessionId();
                String userMessage = context.getRequest().getLastUserMessage();

                // ==================== 子阶段 PLAN：任务规划 ====================
                pipelineCtx.getCurrentStage().set("PLAN");
                context.getTracing().beginStage("PLAN");
                long t3 = System.currentTimeMillis();
                log.info(logJson("INFO", "stage_start", "PLAN", traceId,
                        "Planning task decomposition", null));
                sink.next(sseEvent("plan_start", "Planning task decomposition"));

                // 构建规划请求并调用远程规划服务
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

                // 安全地解析原始节点数据，避免类型转换异常
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rawNodes = planResult != null && planResult.get("nodes") instanceof List
                        ? (List<Map<String, Object>>) planResult.get("nodes")
                        : Collections.emptyList();
                for (Map<String, Object> raw : rawNodes) {
                    // 安全解析 requiredTools 列表
                    @SuppressWarnings("unchecked")
                    List<String> tools = raw.containsKey("requiredTools") && raw.get("requiredTools") instanceof List
                            ? (List<String>) raw.get("requiredTools") : Collections.emptyList();
                    // 安全解析 dependencies 列表
                    @SuppressWarnings("unchecked")
                    List<String> deps = raw.containsKey("dependencies") && raw.get("dependencies") instanceof List
                            ? (List<String>) raw.get("dependencies") : Collections.emptyList();
                    // 超时时间默认为 30000ms
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

                // 逐个发送节点详情给前端，便于展示任务列表
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

                // ==================== 子阶段 EXECUTE：任务执行 ====================
                pipelineCtx.getCurrentStage().set("EXECUTE");
                context.getTracing().beginStage("EXECUTE");
                long t4 = System.currentTimeMillis();
                log.info(logJson("INFO", "stage_start", "EXECUTE", traceId,
                        "Executing " + nodes.size() + " task(s)", null));

                // 逐个顺序执行任务节点
                for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
                    TaskNode node = nodes.get(nodeIndex);
                    // 通知前端当前开始执行哪个任务
                    sink.next(sseEvent("action_start",
                            "{\"index\":" + (nodeIndex + 1) + ",\"total\":" + nodes.size()
                                    + ",\"nodeId\":\"" + escapeJson(node.getNodeId())
                                    + "\",\"description\":\"" + escapeJson(node.getDescription()) + "\"}"));

                    long toolStart = System.currentTimeMillis();
                    try {
                        // 构建工具执行请求并远程调用
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

                        // 执行成功：累加成功计数，保存输出结果
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
                            // 执行失败（远程返回错误）：累加失败计数，记录错误信息
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
                        // 执行异常（网络/超时等）：累加失败计数
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
                // 全部任务执行完毕，发送汇总事件
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
                // 整体阶段异常：降级继续，允许后续 RespondStage 给出降级响应
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
