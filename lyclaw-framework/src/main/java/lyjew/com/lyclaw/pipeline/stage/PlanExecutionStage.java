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
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.*;

@Slf4j
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
        ChatContext chatCtx = new ChatContext(ctx.getChatRequest(), session, null, List.of(), null, null, ctx.getTracing().getTraceId());
        chatCtx.setAttribute("memoryEntries", ctx.getAttribute("memoryEntries"));
        return chatCtx;
    }

    @Override
    public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
        if (ctx.isTerminated()) return Flux.empty();

        return Flux.defer(() -> {
            String traceId = ctx.getTracing().getTraceId();
            List<ServerSentEvent<String>> events = new ArrayList<>();
            try {
                ctx.getCurrentStage().set("PLAN");
                ctx.getTracing().beginStage("PLAN");
                long t3 = System.currentTimeMillis();
                log.info("\n\n========== [阶段 2/5] 任务规划 [PLAN] ==========");
                log.info(logJson("INFO", "stage_start", "PLAN", traceId,
                        "Planning task decomposition", null));
                events.add(sseEvent("plan_start", "Planning task decomposition"));

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
                events.add(sseEvent("plan_complete", "Planned " + ctx.getNodes().size() + " task(s)"));

                List<TaskNode> nodes = ctx.getNodes();
                for (int i = 0; i < nodes.size(); i++) {
                    TaskNode node = nodes.get(i);
                    Map<String, Object> nodeData = new LinkedHashMap<>();
                    nodeData.put("index", i + 1);
                    nodeData.put("nodeId", node.getNodeId());
                    nodeData.put("type", node.getType());
                    nodeData.put("description", node.getDescription());
                    nodeData.put("dependencies", node.getDependencies());
                    events.add(sseEvent("plan_node", nodeData));
                }

                long stageDuration = System.currentTimeMillis() - t3;
                ctx.getTracing().endStage("PLAN");
                log.info(logJson("INFO", "stage_complete", "PLAN", traceId,
                        "Plan decomposition complete", stageDuration));
                if (metricsCollector != null) {
                    metricsCollector.recordPipelineStage("PLAN", stageDuration);
                }

                ctx.getCurrentStage().set("EXECUTE");
                Map<String, Object> completeData = new LinkedHashMap<>();
                completeData.put("total", nodes.size());
                completeData.put("success", 0);
                completeData.put("failed", 0);
                events.add(sseEvent("action_complete", completeData));
            } catch (Exception e) {
                log.error(logJson("ERROR", "stage_error", "PLAN", traceId,
                        "Plan/execute failed: " + e.getMessage(), null), e);
                ctx.getCurrentStage().set("EXECUTE");
                events.add(sseEvent("plan_complete", "Plan execution degraded"));
            }
            return Flux.fromIterable(events);
        });
    }

    @Override
    public int getOrder() { return 2; }

    @Override
    public String getStageName() { return "PlanExecution"; }
}
