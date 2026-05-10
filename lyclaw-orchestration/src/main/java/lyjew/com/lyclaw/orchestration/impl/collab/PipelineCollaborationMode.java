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
public class PipelineCollaborationMode implements CollaborationMode {

    public static final String MODE_ID = "pipeline";

    private final ConcurrentHashMap<String, Double> progressMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> cancelMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> stageResults = new ConcurrentHashMap<>();

    @Override
    public String getModeId() {
        return MODE_ID;
    }

    @Override
    public TopologyType getPreferredTopology() {
        return TopologyType.HIERARCHICAL;
    }

    @Override
    public AssignmentPlan assign(List<AgentHandle> availableAgents, OrchestrationContext ctx) {
        if (availableAgents == null || availableAgents.isEmpty()) {
            return AssignmentPlan.builder()
                    .assignments(Collections.emptyList())
                    .communicationChannels(Collections.emptyMap())
                    .build();
        }

        List<AssignmentPlan.Assignment> assignments = new ArrayList<>();
        Map<String, List<String>> channels = new HashMap<>();

        for (int i = 0; i < availableAgents.size(); i++) {
            AgentHandle agent = availableAgents.get(i);
            assignments.add(AssignmentPlan.Assignment.builder()
                    .agentId(agent.getAgentId())
                    .taskNodeId("stage-" + (i + 1))
                    .role("stage_processor")
                    .priority(i + 1)
                    .build());
        }

        for (int i = 0; i < availableAgents.size() - 1; i++) {
            String from = availableAgents.get(i).getAgentId();
            String to = availableAgents.get(i + 1).getAgentId();
            channels.put(from + "->" + to, List.of("output_to_next_stage", "data_handoff"));
        }

        log.info("[PipelineCollab] Assignment: {} stages in pipeline", availableAgents.size());

        return AssignmentPlan.builder()
                .assignments(assignments)
                .communicationChannels(channels)
                .build();
    }

    @Override
    public CompletableFuture<AgentResult> execute(CollaborationContext ctx) {
        String collabId = ctx.getCollaborationId();
        List<AgentHandle> participants = ctx.getParticipants();

        log.info("[PipelineCollab] Executing pipeline: collabId={}, stages={}",
                collabId, participants != null ? participants.size() : 0);

        return CompletableFuture.supplyAsync(() -> {
            try {
                if (participants == null || participants.isEmpty()) {
                    return new AgentResult(collabId, "FAILED",
                            "No participants for pipeline", "", 0);
                }

                long startMs = System.currentTimeMillis();
                List<String> stageOutputs = new ArrayList<>();
                String previousOutput = String.valueOf(
                        ctx.getSharedState().getOrDefault("initialInput", "pipeline input"));

                for (int stageIdx = 0; stageIdx < participants.size(); stageIdx++) {
                    if (cancelMap.getOrDefault(collabId, false)) {
                        return new AgentResult(collabId, "CANCELLED",
                                "Pipeline cancelled at stage " + (stageIdx + 1), "", 0);
                    }

                    AgentHandle agent = participants.get(stageIdx);
                    long stageStart = System.currentTimeMillis();

                    final String stageInput = previousOutput;
                    final int stageNum = stageIdx + 1;

                    log.info("[PipelineCollab] Stage {}/{}: agent={}", stageNum, participants.size(), agent.getAgentId());

                    String stageOutput = "Stage " + stageNum + " output by " + agent.getAgentId()
                            + " (input: " + (stageInput.length() > 50
                                    ? stageInput.substring(0, 50) + "..." : stageInput) + ")";

                    long stageElapsed = System.currentTimeMillis() - stageStart;
                    stageOutputs.add(stageOutput);
                    previousOutput = stageOutput;

                    updateProgress(collabId, (double) stageNum / participants.size());
                    log.info("[PipelineCollab] Stage {} completed in {}ms", stageNum, stageElapsed);
                }

                stageResults.put(collabId, List.copyOf(stageOutputs));

                long totalMs = System.currentTimeMillis() - startMs;
                progressMap.put(collabId, 1.0);

                String finalOutput = stageOutputs.isEmpty() ? "pipeline empty" : stageOutputs.get(stageOutputs.size() - 1);
                log.info("[PipelineCollab] Pipeline completed: collabId={}, stages={}, durationMs={}",
                        collabId, participants.size(), totalMs);

                return new AgentResult("pipeline-" + collabId, "COMPLETED",
                        "Pipeline completed in " + participants.size() + " stage(s)",
                        finalOutput, totalMs);
            } catch (Exception e) {
                log.error("[PipelineCollab] Pipeline failed: collabId={}, error={}",
                        collabId, e.getMessage(), e);
                return new AgentResult(collabId, "FAILED",
                        "Pipeline failed: " + e.getMessage(),
                        e.toString(), 0);
            }
        });
    }

    @Override
    public boolean cancel(String collaborationId) {
        cancelMap.put(collaborationId, true);
        progressMap.remove(collaborationId);
        stageResults.remove(collaborationId);
        log.info("[PipelineCollab] Cancelled pipeline: {}", collaborationId);
        return true;
    }

    @Override
    public double getProgress(String collaborationId) {
        return progressMap.getOrDefault(collaborationId, 0.0);
    }

    @Override
    public boolean supportsDynamicScaling() {
        return false;
    }

    private void updateProgress(String collabId, double progress) {
        progressMap.put(collabId, Math.min(1.0, Math.max(0.0, progress)));
    }
}
