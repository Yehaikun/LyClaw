package lyjew.com.lyclaw.action.agent.supervisor;

import lyjew.com.lyclaw.action.agent.DefaultAgentRegistry;
import lyjew.com.lyclaw.action.agent.Orchestrator;
import lyjew.com.lyclaw.action.agent.aggregation.AggregatedResult;
import lyjew.com.lyclaw.action.agent.aggregation.ResultAggregator;
import lyjew.com.lyclaw.action.agent.decomposition.TaskDecomposer;
import lyjew.com.lyclaw.action.agent.decomposition.TaskEdge;
import lyjew.com.lyclaw.action.agent.decomposition.TaskGraph;
import lyjew.com.lyclaw.action.agent.decomposition.TaskNode;
import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.AgentState;
import lyjew.com.lyclaw.agent.CollaborationPattern;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.subagent.SubagentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class SupervisorOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SupervisorOrchestrator.class);

    private final TaskDecomposer decomposer;
    private final Orchestrator orchestrator;
    private final ResultAggregator aggregator;
    private final DefaultAgentRegistry registry;

    public SupervisorOrchestrator(TaskDecomposer decomposer, Orchestrator orchestrator,
                                   ResultAggregator aggregator, DefaultAgentRegistry registry) {
        this.decomposer = decomposer;
        this.orchestrator = orchestrator;
        this.aggregator = aggregator;
        this.registry = registry;
    }

    /**
     * Execute a task in HIERARCHICAL mode: decompose → distribute → aggregate.
     */
    public AggregatedResult executeSupervised(String task, AgentContext ctx) {
        long startTime = System.currentTimeMillis();
        log.info("SupervisorOrchestrator: executing supervised task");

        List<AgentHandle> workers = findWorkers(ctx);
        if (workers.isEmpty()) {
            return AggregatedResult.failure("没有可用的 Worker Agent", CollaborationPattern.HIERARCHICAL, List.of());
        }

        TaskGraph graph = decomposer.decompose(task, workers);
        if (graph.totalNodes() == 0) {
            return AggregatedResult.failure("任务分解失败", CollaborationPattern.HIERARCHICAL, List.of());
        }

        log.info("Task decomposed: {} nodes, {} edges", graph.totalNodes(), graph.getEdges().size());

        List<SubagentResult> results = executeGraph(graph, workers, ctx);

        long elapsed = System.currentTimeMillis() - startTime;
        return aggregator.aggregate(CollaborationPattern.HIERARCHICAL, task, results, ctx);
    }

    private List<SubagentResult> executeGraph(TaskGraph graph, List<AgentHandle> workers, AgentContext ctx) {
        List<SubagentResult> results = new ArrayList<>();
        int maxRounds = graph.totalNodes() * 2;
        int round = 0;

        while (!graph.isComplete() && round < maxRounds) {
            round++;
            List<TaskNode> readyNodes = graph.getNextNodes(null);

            if (graph.getRootNodes().stream().anyMatch(n -> n.getStatus() == TaskNode.Status.PENDING)) {
                readyNodes = graph.getRootNodes();
            } else {
                readyNodes.clear();
                for (TaskNode n : graph.getNodes()) {
                    if (n.getStatus() == TaskNode.Status.PENDING) {
                        List<String> deps = graph.getEdges().stream()
                                .filter(e -> e.getToNodeId().equals(n.getId()))
                                .map(TaskEdge::getFromNodeId)
                                .collect(Collectors.toList());
                        boolean allDepsMet = deps.stream().allMatch(depId ->
                                graph.getNodes().stream()
                                        .filter(on -> on.getId().equals(depId))
                                        .allMatch(on -> on.getStatus() == TaskNode.Status.COMPLETED));
                        if (allDepsMet) {
                            readyNodes.add(n);
                        }
                    }
                }
            }

            if (readyNodes.isEmpty() && !graph.isComplete()) {
                log.warn("Graph stalled: {} remaining, breaking",
                        graph.getNodes().stream().filter(n -> n.getStatus() == TaskNode.Status.PENDING).count());
                break;
            }

            for (TaskNode node : readyNodes) {
                node.setStatus(TaskNode.Status.RUNNING);
                String agentId = assignAgent(node, workers);
                node.setAssignedAgentId(agentId);
                log.info("Executing node: {} via agent {}", node.getId(), agentId);

                try {
                    SubagentResult result = orchestrator.orchestrate(agentId, node.getDescription(), "auto", ctx);
                    node.setResult(result);
                    node.setStatus(result.isSuccess() ? TaskNode.Status.COMPLETED : TaskNode.Status.FAILED);
                    results.add(result);
                } catch (Exception e) {
                    log.error("Node {} failed: {}", node.getId(), e.getMessage());
                    node.setStatus(TaskNode.Status.FAILED);
                    results.add(SubagentResult.error("Node execution failed: " + e.getMessage()));
                }
            }
        }

        return results;
    }

    private String assignAgent(TaskNode node, List<AgentHandle> workers) {
        if (node.getCandidates() != null && !node.getCandidates().isEmpty()) {
            return node.getCandidates().get(0);
        }

        // Round-robin among idle workers
        List<AgentHandle> idle = workers.stream()
                .filter(w -> w.getState() == AgentState.IDLE || w.getState() == AgentState.RUNNING)
                .collect(Collectors.toList());
        if (!idle.isEmpty()) {
            return idle.get(0).getAgentId();
        }
        return workers.get(0).getAgentId();
    }

    private List<AgentHandle> findWorkers(AgentContext ctx) {
        return registry.getAllAgents().stream()
                .filter(h -> h.getCollaborationMode() == lyjew.com.lyclaw.agent.AgentCollaborationMode.WORKER
                        || h.getCollaborationMode() == lyjew.com.lyclaw.agent.AgentCollaborationMode.NONE)
                .filter(h -> !h.getAgentId().equals(ctx.getAgentId()))
                .collect(Collectors.toList());
    }
}
