package lyjew.com.lyclaw.orchestration.stage;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.task.PlanValidator;
import lyjew.com.lyclaw.task.TaskPlanner;
import lyjew.com.lyclaw.task.TaskPlan;
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
 * <h3>核心职责</h3>
 * 本阶段是编排管线中的任务规划环节，负责将用户的自然语言意图转化为结构化的任务分解方案。
 * 它通过任务规划器（TaskPlanner）生成一组有序的任务节点（TaskNode），
 * 每个节点包含节点 ID、任务类型、描述、所需工具列表、依赖关系和超时时间等元数据。
 * 这些任务节点随后被推入 PipelineContext 中，供下游阶段（ReflectionStage、RespondStage）使用。
 *
 * <h3>不属于本阶段的职责</h3>
 * 需要特别澄清以下误解：<b>本阶段不执行任何工具调用</b>。
 * 具体的工具执行逻辑已完全内嵌在 RespondStage 的 ReAct（推理-行动）循环中。
 * 在 ReAct 循环中，LLM 会根据通过 ToolRegistry.getAllDefinitions() 获取的完整工具列表，
 * 自主推理决定调用哪个工具、传递什么参数、何时停止迭代。
 * 这种设计将"规划"与"执行"解耦——PlanExecutionStage 只负责生成宏观任务计划，
 * 而微观的工具调用决策完全由 LLM 在 RespondStage 中动态完成。
 * RespondStage 内部的 ReAct 循环是一个实现细节，并非独立的管线阶段，
 * 不要将其误解为名为 "ToolCallLoop" 的独立阶段或类。
 *
 * <h3>执行流程</h3>
 * <ol>
 *   <li>检查流水线是否已被上游终止（terminated），若是则跳过本阶段。</li>
 *   <li>子阶段 PLAN：构建 PlanRequest（包含 sessionId、userIntent、strategy），
 *       通过 TaskPlanner.plan() 进行任务规划，获取 TaskPlan。</li>
 *   <li>安全解析规划结果中的原始节点列表（rawNodes），对每个节点提取 nodeId、type、
 *       description、requiredTools、dependencies、timeoutMs 等字段。</li>
 *   <li>将所有解析后的 TaskNode 推入 PipelineContext，并逐个向前端推送节点详情 SSE 事件。</li>
 *   <li>子阶段 EXECUTE 的标记已保留，但实际工具调用由 RespondStage 接管，
 *       本阶段仅发送一条占位 SSE 事件，声明执行已延迟到 ReAct 循环。</li>
 *   <li>记录 PLAN 子阶段的耗时指标到 MetricsCollector（如果可用）。</li>
 * </ol>
 *
 * <h3>异常处理</h3>
 * 如果规划服务调用失败或解析过程中出现异常，本阶段不会直接中断整个管线，
 * 而是记录错误日志并以降级模式（degraded）继续，允许后续 RespondStage 给出降级响应。
 * 这种设计保证了即使远程规划服务不可用，用户仍能收到有意义的反馈。
 *
 * @see lyjew.com.lyclaw.task.TaskPlanner
 * @see lyjew.com.lyclaw.orchestration.stage.RespondStage
 * @see lyjew.com.lyclaw.task.TaskNode
 */

@Slf4j
@PipelineStage(name = "PlanExecution", after = SecurityCheckStage.class, group = "CORE")
public class PlanExecutionStage extends PipelineStageBase {

    private final TaskPlanner taskPlanner;
    private final PlanValidator planValidator;
    private final MetricsCollector metricsCollector;

    /**
     * 构造计划执行阶段实例。
     *
     * <p>通过 Spring 依赖注入接收任务规划器和可选的计划校验器、指标采集器。
     * MetricsCollector 使用 @Nullable 标记，允许在无指标采集器的环境下正常运行，
     * 此时仅跳过指标记录逻辑，不影响核心规划流程。
     *
     * @param taskPlanner 任务规划器（通过 @Qualifier("DAGTaskPlanner") 注入），用于将用户意图分解为 TaskPlan
     * @param planValidator 计划校验器，可为 null，用于校验规划结果
     * @param metricsCollector 指标采集器，可为 null，用于记录子阶段 PLAN 的耗时指标
     */
    public PlanExecutionStage(
            @org.springframework.beans.factory.annotation.Qualifier("DAGTaskPlanner") TaskPlanner taskPlanner,
            @org.springframework.lang.Nullable PlanValidator planValidator,
            @org.springframework.lang.Nullable MetricsCollector metricsCollector) {
        this.taskPlanner = taskPlanner;
        this.planValidator = planValidator;
        this.metricsCollector = metricsCollector;
    }

    /**
     * 执行任务规划流程。
     *
     * <p>本方法实现了从用户意图到结构化任务列表的完整转换流程。首先检查流水线是否已被
     * 上游阶段终止，若不终止则进入 PLAN 子阶段：构建包含 sessionId、userIntent 和
     * strategy 的 PlanRequest 对象，通过 TaskPlanner 直接进行任务规划获取 TaskPlan。
     * 解析返回的原始节点数据（rawNodes），安全地提取每个节点的 nodeId、type、description、
     * requiredTools、dependencies 和 timeoutMs 等字段，构造 TaskNode 对象并推入
     * PipelineContext 中。随后逐个向前端推送节点详情的 SSE 事件，便于用户界面展示任务列表。
     * 最后标记进入 EXECUTE 子阶段并发送占位事件，声明实际的工具调用已延迟到 RespondStage
     * 的 ReAct 循环中执行。</p>
     *
     * <p>特别注意：工具调用不在此阶段执行。编排模块中不存在名为 ToolCallLoop 的独立类或阶段，
     * 工具调用是 RespondStage 内部 ReAct 循环的实现细节。LLM 通过 ToolRegistry.getAllDefinitions()
     * 获取所有可用工具定义后自主决定调用策略，编排器不做预设的工具映射。</p>
     *
     * <p>异常处理策略为降级继续（degrade-and-continue）：当规划服务调用失败或节点解析异常时，
     * 不中断整个管线，而是记录错误日志后发送降级完成事件，让后续 RespondStage 有机会提供
     * 降级响应，确保用户始终能收到反馈。</p>
     *
     * @param context     当前聊天上下文，包含会话信息、用户消息和追踪数据
     * @param pipelineCtx 流水线上下文，承载任务节点列表和各阶段累积状态
     * @return SSE 事件流，包含 plan_start、plan_node、plan_complete、action_complete 等事件
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
                log.info("\n\n══════════════════════════════════");
                log.info("  [阶段 2/6] 任务规划 - 分解用户意图为执行计划 [PLAN]");
                log.info("══════════════════════════════════");
                log.info(logJson("INFO", "stage_start", "PLAN", traceId,
                        "Planning task decomposition", null));
                sink.next(sseEvent("plan_start", "Planning task decomposition"));

                // 构建规划请求并使用 TaskPlanner 直接规划（替代原 PlanFeignClient 远程调用）
                PlanRequest planReq = PlanRequest.builder()
                        .sessionId(sessionId)
                        .userIntent(userMessage)
                        .strategy("default")
                        .context(Map.of("sessionId", sessionId, "timestamp", System.currentTimeMillis()))
                        .build();
                long planCallStart = System.currentTimeMillis();
                // TaskPlanner.plan() 需要 ChatContext 和 userIntent 字符串
                TaskPlan taskPlan = taskPlanner.plan(context, userMessage);
                // 校验计划（如果校验器可用）
                if (planValidator != null) {
                    planValidator.validate(taskPlan);
                }
                // 将 TaskPlan 节点转换为下游代码期望的格式
                List<Map<String, Object>> rawNodes = new ArrayList<>();
                if (taskPlan != null && taskPlan.getNodes() != null) {
                    for (TaskNode node : taskPlan.getNodes()) {
                        Map<String, Object> raw = new LinkedHashMap<>();
                        raw.put("nodeId", node.getNodeId());
                        raw.put("type", node.getType());
                        raw.put("description", node.getDescription());
                        raw.put("requiredTools", node.getRequiredTools());
                        raw.put("dependencies", node.getDependencies());
                        raw.put("timeoutMs", node.getTimeoutMs());
                        rawNodes.add(raw);
                    }
                }
                long planCallDuration = System.currentTimeMillis() - planCallStart;
                log.info(logJson("INFO", "planner_call", "PLAN", traceId,
                        "taskPlanner.plan completed, " + rawNodes.size() + " nodes", planCallDuration));
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

                // ==================== EXECUTE 子阶段已由 RespondStage 的 ReAct 循环接管 ====================
                // 工具调用不再由编排器预设映射（node.getType() 不是工具名），
                // 而是由 RespondStage 通过 ToolRegistry.getAllDefinitions() 获取所有可用工具定义，传入 LLM，
                // LLM 在 ReAct 循环中自主决定调用哪个工具、传什么参数。
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

    /**
     * 返回本阶段在管线中的执行顺序编号。
     *
     * <p>返回值为 2，表示 PlanExecutionStage 是编排管线中的第三个阶段
     *（排在 ContextBuildStage(0) 和 SecurityCheckStage(1) 之后）。
     * PipelineStageProcessor 会根据此编号对所有阶段进行升序排序，
     * 确保阶段之间按照正确的依赖关系依次执行。此编号也用于日志输出和
     * 指标采集中标识阶段位置。</p>
     *
     * @return 阶段顺序编号，固定为 2
     */
    @Override
    public int getOrder() { return 2; }

    /**
     * 返回本阶段的名称标识。
     *
     * <p>返回固定字符串 "PlanExecution"，作为本阶段在整个编排管线中的唯一标识符。
     * 该名称用于日志记录、指标上报、SSE 事件标注以及 Tracing 追踪中的阶段标记。
     * PipelineStageProcessor 在排序和查找阶段时也会使用此名称进行匹配。</p>
     *
     * @return 阶段名称，固定为 "PlanExecution"
     */
    @Override
    public String getStageName() { return "PlanExecution"; }
}
