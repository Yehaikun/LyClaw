package lyjew.com.lyclaw.task.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.task.TaskNode;
import lyjew.com.lyclaw.task.TaskPlan;
import lyjew.com.lyclaw.task.TaskPlanner;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * TaskPlan 的默认实现 —— 内部类，仅在此规划器内部使用。
 *
 * <p>持有节点列表和依赖映射，通过 TaskNode 的 dependencies 字段判断就绪状态。</p>
 */
class DefaultTaskPlan implements TaskPlan {

    private final List<TaskNode> nodes;
    private final long estimatedCompletionTime;

    DefaultTaskPlan(List<TaskNode> nodes) {
        this.nodes = nodes;
        this.estimatedCompletionTime = nodes.size() * 1000L; // 每个节点估算 1s
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
        // 所有节点都就绪才算就绪
        return !nodes.isEmpty();
    }
}

/**
 * 默认任务规划器 —— 贪心策略：按顺序拆解 Agent 请求为 TaskNode 列表。
 *
 * <p><b>设计动机</b>：TaskPlanner 是 Agent 的"大脑"，决定了如何将用户请求
 * 拆解为可执行的任务节点。默认实现采用简单策略——按顺序逐个执行。
 * 复杂场景可替换为更智能的规划器（如基于 DAG 的并行规划）。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see TaskPlanner
 * @see TaskPlan
 */
@Component
public class DefaultTaskPlanner implements TaskPlanner {

    @Override
    public TaskPlan plan(ChatContext context) {
        // 贪心策略：将请求拆解为按顺序执行的任务节点
        List<TaskNode> nodes = new ArrayList<>();

        // 1. 分析请求类型
        String userMessage = context.getRequest().getLastUserMessage();

        // 2. 创建根任务节点 —— 依赖关系在构造器中通过 List<String> 传入
        TaskNode root = new TaskNode("root", "ANALYZE", userMessage,
                List.of(), List.of(), 5000L);
        nodes.add(root);

        // 3. 创建子任务节点，依赖 root
        TaskNode execute = new TaskNode("execute", "EXECUTE", userMessage,
                List.of(), List.of(root.getNodeId()), 10000L);
        nodes.add(execute);

        // 4. 通过 DefaultTaskPlan 内部类创建 TaskPlan
        return new DefaultTaskPlan(nodes);
    }

    @Override
    public TaskPlan optimize(AgentResult result) {
        return null;
    }
}