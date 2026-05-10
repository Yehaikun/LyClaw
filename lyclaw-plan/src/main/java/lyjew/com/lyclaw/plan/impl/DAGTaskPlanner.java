package lyjew.com.lyclaw.plan.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.task.DecompositionStrategy;
import lyjew.com.lyclaw.task.PlanGraph;
import lyjew.com.lyclaw.task.ReflectionFeedback;
import lyjew.com.lyclaw.task.TaskNode;
import lyjew.com.lyclaw.task.TaskPlan;
import lyjew.com.lyclaw.task.TaskPlanner;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * TaskPlan 的简单实现 —— 内部类，仅在此规划器内部使用。
 */
class SimpleTaskPlan implements TaskPlan {

    private final List<TaskNode> nodes;
    private final long estimatedCompletionTime;

    SimpleTaskPlan(List<TaskNode> nodes) {
        this.nodes = nodes;
        this.estimatedCompletionTime = nodes.size() * 1000L;
    }

    @Override
    public List<TaskNode> getNodes() {
        return nodes;
    }

    @Override
    public List<String> getDependencies(String nodeId) {
        for (TaskNode node : nodes) {
            if (node.getNodeId().equals(nodeId)) {
                return node.getDependencies();
            }
        }
        return List.of();
    }

    @Override
    public long getEstimatedCompletionTime() {
        return estimatedCompletionTime;
    }

    @Override
    public boolean isReady() {
        return !nodes.isEmpty();
    }
}

/**
 * DAG 任务规划器 —— 基于有向无环图的智能任务拆解。
 *
 * <p>使用 DAG 结构将复杂任务拆解为可并行执行的子任务节点。
 * 当前为 stub 实现，后续将接入 LLM 进行智能拆解。</p>
 *
 * @since 2.0
 * @author LyClaw Team
 * @see TaskPlanner
 * @see TaskPlan
 */
@Service
public class DAGTaskPlanner implements TaskPlanner {

    @Override
    public TaskPlan plan(ChatContext context) {
        String description = extractDescription(context, null);
        TaskNode node = new TaskNode("dag-root-1", "EXECUTE", description,
                List.of(), List.of(), 30000L);
        return new SimpleTaskPlan(List.of(node));
    }

    @Override
    public TaskPlan plan(ChatContext context, String userIntent) {
        String description = extractDescription(context, userIntent);
        TaskNode node = new TaskNode("dag-root-1", "EXECUTE", description,
                List.of(), List.of(), 30000L);
        return new SimpleTaskPlan(List.of(node));
    }

    @Override
    public TaskPlan revise(TaskPlan original, ReflectionFeedback feedback) {
        return original;
    }

    @Override
    public TaskPlan optimize(AgentResult previousResult) {
        if (previousResult == null) {
            return null;
        }
        TaskNode node = new TaskNode("opt-1", "OPTIMIZE",
                "Optimized based on: " + previousResult.getSummary(),
                List.of(), List.of(), 30000L);
        return new SimpleTaskPlan(List.of(node));
    }

    @Override
    public PlanGraph decompose(TaskNode rootTask, DecompositionStrategy strategy) {
        PlanGraph graph = new PlanGraph();
        graph.addNode(rootTask);
        return graph;
    }

    private String extractDescription(ChatContext context, String userIntent) {
        if (userIntent != null && !userIntent.isBlank()) {
            return userIntent;
        }
        if (context != null && context.getRequest() != null) {
            String lastMsg = context.getRequest().getLastUserMessage();
            if (lastMsg != null && !lastMsg.isBlank()) {
                return lastMsg;
            }
        }
        return "default task";
    }
}
