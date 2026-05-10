package lyjew.com.lyclaw.orchestration.impl.collab;

import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.AgentState;
import lyjew.com.lyclaw.agent.collab.*;
import lyjew.com.lyclaw.orchestration.OrchestrationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CollaborationModesTest {

    private List<AgentHandle> agents;

    @BeforeEach
    void setUp() {
        agents = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            agents.add(AgentHandle.builder()
                    .agentId("agent-" + i).name("Agent" + i)
                    .state(AgentState.IDLE)
                    .capabilities(List.of("coding", "writing"))
                    .historicalAccuracy(0.5 + i * 0.1)
                    .build());
        }
    }

    // ========== SupervisorWorkerMode ==========

    @Test
    @DisplayName("SupervisorWorker: topology is STAR")
    void supervisorWorkerTopologyIsStar() {
        SupervisorWorkerMode mode = new SupervisorWorkerMode();
        assertThat(mode.getModeId()).isEqualTo("supervisor_worker");
        assertThat(mode.getPreferredTopology()).isEqualTo(TopologyType.STAR);
        assertThat(mode.supportsDynamicScaling()).isTrue();
    }

    @Test
    @DisplayName("SupervisorWorker: assign creates supervisor + workers")
    void supervisorWorkerAssign() {
        SupervisorWorkerMode mode = new SupervisorWorkerMode();
        OrchestrationContext ctx = OrchestrationContext.builder().tasks(List.of()).build();

        AssignmentPlan plan = mode.assign(agents, ctx);

        List<AssignmentPlan.Assignment> assignments = plan.getAssignments();
        assertThat(assignments).isNotEmpty();
        // First agent is supervisor
        assertThat(assignments.get(0).getRole()).isEqualTo("supervisor");
        // Remaining are workers
        long workers = assignments.stream().filter(a -> "worker".equals(a.getRole())).count();
        assertThat(workers).isEqualTo(agents.size() - 1);
    }

    @Test
    @DisplayName("SupervisorWorker: empty agent list produces empty plan")
    void supervisorWorkerEmptyList() {
        SupervisorWorkerMode mode = new SupervisorWorkerMode();
        OrchestrationContext ctx = OrchestrationContext.builder().build();
        AssignmentPlan plan = mode.assign(List.of(), ctx);
        assertThat(plan.getAssignments()).isEmpty();
    }

    @Test
    @DisplayName("SupervisorWorker: null agent list produces empty plan")
    void supervisorWorkerNullList() {
        SupervisorWorkerMode mode = new SupervisorWorkerMode();
        AssignmentPlan plan = mode.assign(null, OrchestrationContext.builder().build());
        assertThat(plan.getAssignments()).isEmpty();
    }

    @Test
    @DisplayName("SupervisorWorker: cancel and progress")
    void supervisorWorkerCancelProgress() {
        SupervisorWorkerMode mode = new SupervisorWorkerMode();
        assertThat(mode.cancel("collab-1")).isTrue();
        assertThat(mode.getProgress("collab-1")).isEqualTo(0.0);
    }

    // ========== NetworkCollaborationMode ==========

    @Test
    @DisplayName("Network: topology is MESH")
    void networkTopologyIsMesh() {
        NetworkCollaborationMode mode = new NetworkCollaborationMode(null);
        assertThat(mode.getModeId()).isEqualTo("network");
        assertThat(mode.getPreferredTopology()).isEqualTo(TopologyType.MESH);
        assertThat(mode.supportsDynamicScaling()).isTrue();
    }

    @Test
    @DisplayName("Network: assign creates full mesh channels")
    void networkAssignFullMesh() {
        NetworkCollaborationMode mode = new NetworkCollaborationMode(null);
        List<AgentHandle> threeAgents = agents.subList(0, 3);

        AssignmentPlan plan = mode.assign(threeAgents, OrchestrationContext.builder().build());

        // All agents are peers
        assertThat(plan.getAssignments()).hasSize(3);
        plan.getAssignments().forEach(a -> assertThat(a.getRole()).isEqualTo("peer"));

        // Full mesh: n*(n-1)/2 bidirectional = n*(n-1) channels
        // For 3 agents: 3 * 2 = 6 channels
        assertThat(plan.getCommunicationChannels()).hasSize(6);
    }

    @Test
    @DisplayName("Network: empty agent list produces empty plan")
    void networkEmptyList() {
        NetworkCollaborationMode mode = new NetworkCollaborationMode(null);
        AssignmentPlan plan = mode.assign(List.of(), OrchestrationContext.builder().build());
        assertThat(plan.getAssignments()).isEmpty();
    }

    @Test
    @DisplayName("Network: cancel and progress")
    void networkCancelProgress() {
        NetworkCollaborationMode mode = new NetworkCollaborationMode(null);
        assertThat(mode.cancel("collab-2")).isTrue();
        assertThat(mode.getProgress("collab-2")).isEqualTo(0.0);
    }

    // ========== MarketCollaborationMode ==========

    @Test
    @DisplayName("Market: topology is STAR")
    void marketTopologyIsStar() {
        MarketCollaborationMode mode = new MarketCollaborationMode(null);
        assertThat(mode.getModeId()).isEqualTo("market");
        assertThat(mode.getPreferredTopology()).isEqualTo(TopologyType.STAR);
        assertThat(mode.supportsDynamicScaling()).isTrue();
    }

    @Test
    @DisplayName("Market: assign creates auctioneer + bidders")
    void marketAssignAuctioneerAndBidders() {
        MarketCollaborationMode mode = new MarketCollaborationMode(null);

        AssignmentPlan plan = mode.assign(agents, OrchestrationContext.builder().build());

        // First agent is auctioneer
        assertThat(plan.getAssignments().get(0).getRole()).isEqualTo("auctioneer");
        // Remaining are bidders
        long bidders = plan.getAssignments().stream().filter(a -> "bidder".equals(a.getRole())).count();
        assertThat(bidders).isEqualTo(agents.size() - 1);
    }

    @Test
    @DisplayName("Market: channels connect auctioneer to each bidder")
    void marketChannels() {
        MarketCollaborationMode mode = new MarketCollaborationMode(null);
        List<AgentHandle> three = agents.subList(0, 3);

        AssignmentPlan plan = mode.assign(three, OrchestrationContext.builder().build());

        // 2 bidders, 2 channels each direction = 4 channels
        assertThat(plan.getCommunicationChannels()).hasSize(4);
    }

    @Test
    @DisplayName("Market: empty agent list produces empty plan")
    void marketEmptyList() {
        MarketCollaborationMode mode = new MarketCollaborationMode(null);
        AssignmentPlan plan = mode.assign(List.of(), OrchestrationContext.builder().build());
        assertThat(plan.getAssignments()).isEmpty();
    }

    @Test
    @DisplayName("Market: cancel clears auction results too")
    void marketCancelClearsAuction() {
        MarketCollaborationMode mode = new MarketCollaborationMode(null);
        assertThat(mode.cancel("auction-1")).isTrue();
        assertThat(mode.getProgress("auction-1")).isEqualTo(0.0);
    }

    // ========== PipelineCollaborationMode ==========

    @Test
    @DisplayName("Pipeline: topology is HIERARCHICAL")
    void pipelineTopologyIsHierarchical() {
        PipelineCollaborationMode mode = new PipelineCollaborationMode();
        assertThat(mode.getModeId()).isEqualTo("pipeline");
        assertThat(mode.getPreferredTopology()).isEqualTo(TopologyType.HIERARCHICAL);
        assertThat(mode.supportsDynamicScaling()).isFalse();
    }

    @Test
    @DisplayName("Pipeline: assign creates linear stages")
    void pipelineAssignLinearStages() {
        PipelineCollaborationMode mode = new PipelineCollaborationMode();

        AssignmentPlan plan = mode.assign(agents, OrchestrationContext.builder().build());

        // All agents are stage processors
        assertThat(plan.getAssignments()).hasSize(5);
        plan.getAssignments().forEach(a -> assertThat(a.getRole()).isEqualTo("stage_processor"));
        plan.getAssignments().forEach(a -> assertThat(a.getTaskNodeId()).startsWith("stage-"));

        // Linear pipeline: n-1 channels
        assertThat(plan.getCommunicationChannels()).hasSize(4);
    }

    @Test
    @DisplayName("Pipeline: single agent produces 0 channels")
    void pipelineSingleAgentNoChannels() {
        PipelineCollaborationMode mode = new PipelineCollaborationMode();
        List<AgentHandle> single = agents.subList(0, 1);

        AssignmentPlan plan = mode.assign(single, OrchestrationContext.builder().build());

        assertThat(plan.getAssignments()).hasSize(1);
        assertThat(plan.getCommunicationChannels()).isEmpty();
    }

    @Test
    @DisplayName("Pipeline: empty agent list produces empty plan")
    void pipelineEmptyList() {
        PipelineCollaborationMode mode = new PipelineCollaborationMode();
        AssignmentPlan plan = mode.assign(List.of(), OrchestrationContext.builder().build());
        assertThat(plan.getAssignments()).isEmpty();
    }

    @Test
    @DisplayName("Pipeline: cancel and progress")
    void pipelineCancelProgress() {
        PipelineCollaborationMode mode = new PipelineCollaborationMode();
        assertThat(mode.cancel("pipeline-1")).isTrue();
        assertThat(mode.getProgress("pipeline-1")).isEqualTo(0.0);
    }
}
