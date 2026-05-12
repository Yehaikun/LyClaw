package lyjew.com.lyclaw.orchestration.stage;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.feign.PlanFeignClient;
import lyjew.com.lyclaw.infra.metrics.MetricsCollector;
import lyjew.com.lyclaw.pipeline.PipelineContext;
import lyjew.com.lyclaw.task.PlanRequest;
import lyjew.com.lyclaw.task.TaskNode;
import org.springframework.http.codec.ServerSentEvent;
import lyjew.com.lyclaw.annotation.PipelineStage;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * 计划执行阶段，属于核心（CORE）组，在 SecurityCheckStage 之后执行。
 *
 * <p>职责：调用远程规划服务将用户意图分解为多个可执行的任务节点（TaskNode）。
 * 工具调用已移至 RespondStage 通过 ToolCallLoop (ReAct循环) 执行，
 * LLM 根据 ToolRegistry 中的完整工具列表自主决定调用哪个工具、传什么参数。
 *
 * <p>执行顺序：第 2 位（getOrder 返回 2）。
 */
@Slf4j
@PipelineStage(name = "PlanExecution", after = SecurityCheckStage.class, group = "CORE")
public class PlanExecutionStage extends PipelineStageBase {

    private final PlanFeignClient planFeignClient;
    private final MetricsCollector metricsCollector;

    public PlanExecutionStage(PlanFeignClient planFeignClient,
                               @org.springframework.lang.Nullable MetricsCollector metricsCollector) {
        this.planFeignClient = planFeignClient;
        this.metricsCollector = metricsCollector;
    }

    /**
     * 执行任务规划流程。
     *
     * <p>调用远程规划服务生成任务节点，通过 SSE 推送进度。
     * 工具执行已移至 RespondStage 的 ToolCallLoop。</p>
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

                // ==================== EXECUTE 子阶段已由 RespondStage 的 ToolCallLoop 接管 ====================
                // 工具调用不再由编排器预设映射（node.getType() 不是工具名），
                // 而是由 RespondStage 从 ToolRegistry 获取工具定义，传入 LLM，
                // LLM 自主决定调用哪个工具、传什么参数（ReAct 循环）。
                pipelineCtx.getCurrentStage().set("EXECUTE");
                sink.next(sseEvent("action_complete",
                        "{\"total\":" + nodes.size() + ",\"success\":0,\"failed\":0,\"note\":\"deferred to ToolCallLoop\"}"));

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
