package lyjew.com.lyclaw.orchestration.impl.collab;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.collab.*;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.orchestration.OrchestrationContext;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class SupervisorWorkerMode implements CollaborationMode {

    public static final String MODE_ID = "supervisor_worker";

    private final ConcurrentHashMap<String, Double> progressMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> cancelMap = new ConcurrentHashMap<>();

    @Override
    public String getModeId() {
        return MODE_ID;
    }

    @Override
    public TopologyType getPreferredTopology() {
        return TopologyType.STAR;
    }

    @Override
    public AssignmentPlan assign(List<AgentHandle> availableAgents, OrchestrationContext ctx) {
        if (availableAgents == null || availableAgents.isEmpty()) {
            log.warn("[SupervisorWorker] No agents available for assignment");
            return AssignmentPlan.builder()
                    .assignments(Collections.emptyList())
                    .communicationChannels(Collections.emptyMap())
                    .build();
        }

        List<AssignmentPlan.Assignment> assignments = new ArrayList<>();
        Map<String, List<String>> channels = new HashMap<>();
        int agentCount = availableAgents.size();

        AgentHandle supervisor = availableAgents.get(0);
        assignments.add(AssignmentPlan.Assignment.builder()
                .agentId(supervisor.getAgentId())
                .taskNodeId("root")
                .role("supervisor")
                .priority(1)
                .build());

        if (ctx.getTasks() != null) {
            List<String> taskIds = new ArrayList<>();
            for (int i = 0; i < ctx.getTasks().size() && i < agentCount - 1; i++) {
                taskIds.add(ctx.getTasks().get(i).getTaskId());
            }

            for (int i = 1; i < agentCount; i++) {
                AgentHandle worker = availableAgents.get(i);
                String taskNodeId = i - 1 < taskIds.size() ? taskIds.get(i - 1) : "subtask-" + i;
                assignments.add(AssignmentPlan.Assignment.builder()
                        .agentId(worker.getAgentId())
                        .taskNodeId(taskNodeId)
                        .role("worker")
                        .priority(5)
                        .build());
            }
        }

        for (int i = 1; i < agentCount; i++) {
            String workerId = availableAgents.get(i).getAgentId();
            channels.put(supervisor.getAgentId() + "->" + workerId,
                    List.of("task_dispatch", "result_collect", "status_query"));
            channels.put(workerId + "->" + supervisor.getAgentId(),
                    List.of("result_report", "error_report", "status_update"));
        }

        log.info("[SupervisorWorker] Assignment plan: supervisor={}, workers={}, taskNodes={}",
                supervisor.getAgentId(), agentCount - 1,
                assignments.stream().filter(a -> "worker".equals(a.getRole())).count());

        return AssignmentPlan.builder()
                .assignments(assignments)
                .communicationChannels(channels)
                .build();
    }

    @Override
    public CompletableFuture<AgentResult> execute(CollaborationContext ctx) {
        String collabId = ctx.getCollaborationId();
        log.info("[SupervisorWorker] Executing collaboration: collabId={}, participants={}",
                collabId, ctx.getParticipants() != null ? ctx.getParticipants().size() : 0);

        return CompletableFuture.supplyAsync(() -> {
            try {
                List<AgentHandle> participants = ctx.getParticipants();
                if (participants == null || participants.isEmpty()) {
                    return new AgentResult(collabId, "FAILED",
                            "No participants for collaboration", "", 0);
                }

                long startMs = System.currentTimeMillis();

                List<CompletableFuture<AgentResult>> workerFutures = new ArrayList<>();
                for (int i = 1; i < participants.size(); i++) {
                    AgentHandle worker = participants.get(i);
                    int workerIndex = i;
                    CompletableFuture<AgentResult> workerFuture = CompletableFuture.supplyAsync(() -> {
                        if (cancelMap.getOrDefault(collabId, false)) {
                            return new AgentResult(worker.getAgentId(), "CANCELLED",
                                    "Cancelled by coordinator", "", 0);
                        }
                        long workerStart = System.currentTimeMillis();
                        String resultPayload = "Worker " + worker.getAgentId()
                                + " completed subtask " + workerIndex;
                        long elapsed = System.currentTimeMillis() - workerStart;
                        updateProgress(collabId, (double) workerIndex / (participants.size() - 1));
                        return new AgentResult(worker.getAgentId(), "COMPLETED",
                                "Subtask completed", resultPayload, elapsed);
                    });
                    workerFutures.add(workerFuture);
                }

                CompletableFuture.allOf(workerFutures.toArray(new CompletableFuture[0])).join();

                List<String> workerOutputs = new ArrayList<>();
                long totalWorkerMs = 0;
                for (CompletableFuture<AgentResult> wf : workerFutures) {
                    AgentResult wr = wf.join();
                    if ("COMPLETED".equals(wr.getStatus())) {
                        workerOutputs.add(wr.getDetail());
                        totalWorkerMs += wr.getElapsedMs();
                    }
                }

                String aggregatedDetail = "Supervisor aggregated " + workerOutputs.size()
                        + " worker results:\n" + String.join("\n", workerOutputs);
                long totalMs = System.currentTimeMillis() - startMs;

                progressMap.put(collabId, 1.0);
                log.info("[SupervisorWorker] Collaboration completed: collabId={}, workers={}, durationMs={}",
                        collabId, workerOutputs.size(), totalMs);

                return new AgentResult("supervisor-" + collabId, "COMPLETED",
                        "Collaboration completed with " + workerOutputs.size() + " workers",
                        aggregatedDetail, totalMs);
            } catch (Exception e) {
                log.error("[SupervisorWorker] Collaboration failed: collabId={}, error={}",
                        collabId, e.getMessage(), e);
                return new AgentResult(collabId, "FAILED",
                        "Collaboration failed: " + e.getMessage(),
                        e.toString(), 0);
            }
        });
    }

    @Override
    public boolean cancel(String collaborationId) {
        cancelMap.put(collaborationId, true);
        progressMap.remove(collaborationId);
        log.info("[SupervisorWorker] Cancelled collaboration: {}", collaborationId);
        return true;
    }

    @Override
    public double getProgress(String collaborationId) {
        return progressMap.getOrDefault(collaborationId, 0.0);
    }

    @Override
    public boolean supportsDynamicScaling() {
        return true;
    }

    private void updateProgress(String collabId, double progress) {
        progressMap.put(collabId, Math.min(1.0, Math.max(0.0, progress)));
    }
}
