package lyjew.com.lyclaw.react;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.task.TaskNode;

/**
 * 计划注入 Hook，在每次 LLM 调用前将 PlanExecutionStage 生成的 TaskNode DAG
 * 注入到对话上下文中。
 *
 * <p>order=40，在 beforeModel 阶段将计划进度格式化为 system message 注入。
 */
public class PlanningHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(PlanningHook.class);

    @Override
    public int getOrder() { return 40; }

    @Override
    public List<Message> beforeModel(List<Message> messages, AgentContext ctx) {
        List<TaskNode> nodes = ctx.getNodes();
        if (nodes.isEmpty()) {
            return messages;
        }

        String planContext = buildPlanContext(nodes, ctx);
        log.debug("PlanningHook: injecting plan context with {} nodes", nodes.size());

        List<Message> enriched = new java.util.ArrayList<>(messages);
        enriched.add(0, Message.system(planContext));
        return enriched;
    }

    @Override
    public String afterModel(String response, AgentContext ctx) {
        List<TaskNode> nodes = ctx.getNodes();
        if (nodes.isEmpty()) {
            return response;
        }

        int completedCount = ctx.getSuccessCount().get() + ctx.getFailCount().get();
        log.debug("PlanningHook: progress {}/{} tasks", completedCount, nodes.size());

        return response;
    }

    private String buildPlanContext(List<TaskNode> nodes, AgentContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Current Plan]\n");
        int completed = ctx.getSuccessCount().get() + ctx.getFailCount().get();

        for (int i = 0; i < nodes.size(); i++) {
            TaskNode node = nodes.get(i);
            String status = i < completed ? "[done]" : "[pending]";
            sb.append("  ").append(i + 1).append(". ").append(status)
                    .append(" ").append(node.getDescription());
            if (node.getRequiredTools() != null && !node.getRequiredTools().isEmpty()) {
                sb.append(" (tools: ").append(String.join(", ", node.getRequiredTools())).append(")");
            }
            sb.append("\n");
        }

        sb.append("\nProgress: ").append(completed).append("/").append(nodes.size())
                .append(" tasks completed.\n");
        return sb.toString();
    }
}
