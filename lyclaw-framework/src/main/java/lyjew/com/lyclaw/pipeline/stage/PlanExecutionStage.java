package lyjew.com.lyclaw.pipeline.stage;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.infra.metrics.MetricsCollector;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.task.PlanValidator;
import lyjew.com.lyclaw.task.TaskNode;
import lyjew.com.lyclaw.task.TaskPlan;
import lyjew.com.lyclaw.task.TaskPlanner;
import lyjew.com.lyclaw.annotation.PipelineStage;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * 计划执行阶段，order=2。将用户意图分解为 TaskNode DAG。
 */
@Slf4j
@PipelineStage(name = "PlanExecution", after = SecurityCheckStage.class, group = "CORE")
public class PlanExecutionStage extends PipelineStageBase {

    private final TaskPlanner taskPlanner;
    private final PlanValidator planValidator;
    private final MetricsCollector metricsCollector;

    public PlanExecutionStage(
            @org.springframework.beans.factory.annotation.Qualifier("hybridPlanner") TaskPlanner taskPlanner,
            @org.springframework.lang.Nullable PlanValidator planValidator,
            @org.springframework.lang.Nullable MetricsCollector metricsCollector) {
        this.taskPlanner = taskPlanner;
        this.planValidator = planValidator;
        this.metricsCollector = metricsCollector;
    }

    private static ChatContext buildChatContext(AgentContext ctx) {
        Session session = new Session();
        session.setSessionId(ctx.getSessionId());
        List<Message> messages = ctx.getChatRequest() != null && ctx.getChatRequest().getMessages() != null
                ? ctx.getChatRequest().getMessages() : List.of();
        session.setMessages(new ArrayList<>(messages));
        ChatContext chatCtx = new ChatContext(ctx.getChatRequest(), session, null, List.of(), null, null);
        chatCtx.setAttribute("memoryEntries", ctx.getAttribute("memoryEntries"));
        return chatCtx;
    }

    @Override
    public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
        if (ctx.isTerminated()) return Flux.empty();

        return Flux.create(sink -> {
            String traceId = ctx.getTracing().getTraceId();
            try {
                ctx.getCurrentStage().set("PLAN");
                ctx.getTracing().beginStage("PLAN");
                long t3 = System.currentTimeMillis();
                log.info("\n\n========== [阶段 2/5] 任务规划 [PLAN] ==========");
                log.info(logJson("INFO", "stage_start", "PLAN", traceId,
                        "Planning task decomposition", null));
                sink.next(sseEvent("plan_start", "Planning task decomposition"));

                TaskPlan taskPlan = taskPlanner.plan(buildChatContext(ctx), ctx.getUserMessage());
                if (planValidator != null) {
                    planValidator.validate(taskPlan);
                }

                if (taskPlan != null && taskPlan.getNodes() != null) {
                    for (TaskNode node : taskPlan.getNodes()) {
                        ctx.addNode(new TaskNode(
                                node.getNodeId(),
                                node.getType(),
                                node.getDescription(),
                                node.getRequiredTools() != null ? node.getRequiredTools() : Collections.emptyList(),
                                node.getDependencies() != null ? node.getDependencies() : Collections.emptyList(),
                                node.getTimeoutMs() > 0 ? node.getTimeoutMs() : 30000L));
                    }
                }
                log.info(logJson("INFO", "plan_result", "PLAN", traceId,
                        "Plan generated: " + ctx.getNodes().size() + " task(s)", null));
                sink.next(sseEvent("plan_complete", "Planned " + ctx.getNodes().size() + " task(s)"));

                List<TaskNode> nodes = ctx.getNodes();
                for (int i = 0; i < nodes.size(); i++) {
                    TaskNode node = nodes.get(i);
                    sink.next(sseEvent("plan_node",
                            "{\"index\":" + (i + 1) + ",\"nodeId\":\"" + escapeJson(node.getNodeId())
                                    + "\",\"type\":\"" + escapeJson(node.getType())
                                    + "\",\"description\":\"" + escapeJson(node.getDescription()) + "\"}"));
                }

                long stageDuration = System.currentTimeMillis() - t3;
                ctx.getTracing().endStage("PLAN");
                log.info(logJson("INFO", "stage_complete", "PLAN", traceId,
                        "Plan decomposition complete", stageDuration));
                if (metricsCollector != null) {
                    metricsCollector.recordPipelineStage("PLAN", stageDuration);
                }

                ctx.getCurrentStage().set("EXECUTE");
                sink.next(sseEvent("action_complete",
                        "{\"total\":" + nodes.size() + ",\"success\":0,\"failed\":0}"));
                sink.complete();
            } catch (Exception e) {
                log.error(logJson("ERROR", "stage_error", "PLAN", traceId,
                        "Plan/execute failed: " + e.getMessage(), null));
                ctx.getCurrentStage().set("EXECUTE");
                sink.next(sseEvent("plan_complete", "Plan execution degraded"));
                sink.complete();
            }
        });
    }

    @Override
    public int getOrder() { return 2; }

    @Override
    public String getStageName() { return "PlanExecution"; }
}
