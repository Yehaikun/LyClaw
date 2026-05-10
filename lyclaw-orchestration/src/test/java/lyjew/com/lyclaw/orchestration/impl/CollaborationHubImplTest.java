package lyjew.com.lyclaw.orchestration.impl;

import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.collab.*;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.orchestration.OrchestrationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class CollaborationHubImplTest {

    private CollaborationHubImpl hub;

    @BeforeEach
    void setUp() {
        hub = new CollaborationHubImpl(List.of());
    }

    // ========== register ==========

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("Registering null mode is skipped")
        void nullModeSkipped() {
            hub.register(null);
            assertThat(hub.getModeCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Registering a valid mode increases count")
        void validModeRegistered() {
            hub.register(new FakeMode("mode1", TopologyType.STAR));
            assertThat(hub.getModeCount()).isEqualTo(1);
            assertThat(hub.getAvailableModes()).contains("mode1");
        }

        @Test
        @DisplayName("Registering same modeId replaces existing")
        void sameIdReplaces() {
            hub.register(new FakeMode("m1", TopologyType.STAR));
            hub.register(new FakeMode("m1", TopologyType.MESH));
            assertThat(hub.getModeCount()).isEqualTo(1);
            assertThat(hub.getMode("m1").get().getPreferredTopology()).isEqualTo(TopologyType.MESH);
        }

        @Test
        @DisplayName("Constructor discovers CollaborationMode beans")
        void constructorDiscoversModes() {
            CollaborationHubImpl hub2 = new CollaborationHubImpl(List.of(
                    new FakeMode("m1", TopologyType.STAR),
                    new FakeMode("m2", TopologyType.MESH)));

            assertThat(hub2.getModeCount()).isEqualTo(2);
            assertThat(hub2.getAvailableModes()).containsExactlyInAnyOrder("m1", "m2");
        }
    }

    // ========== getMode ==========

    @Nested
    @DisplayName("getMode")
    class GetMode {

        @Test
        @DisplayName("Existing mode returns Optional with value")
        void existingModeReturnsPresent() {
            hub.register(new FakeMode("m1", TopologyType.STAR));
            Optional<CollaborationMode> found = hub.getMode("m1");
            assertThat(found).isPresent();
            assertThat(found.get().getModeId()).isEqualTo("m1");
        }

        @Test
        @DisplayName("Non-existent mode returns empty")
        void nonExistentReturnsEmpty() {
            assertThat(hub.getMode("unknown")).isEmpty();
        }

        @Test
        @DisplayName("Null modeId returns empty")
        void nullModeIdReturnsEmpty() {
            assertThat(hub.getMode(null)).isEmpty();
        }

        @Test
        @DisplayName("Empty modeId returns empty")
        void emptyModeIdReturnsEmpty() {
            assertThat(hub.getMode("")).isEmpty();
        }
    }

    // ========== listModes ==========

    @Test
    @DisplayName("listModes returns sorted by modeId")
    void listModesSorted() {
        hub.register(new FakeMode("c", TopologyType.STAR));
        hub.register(new FakeMode("a", TopologyType.MESH));
        hub.register(new FakeMode("b", TopologyType.HIERARCHICAL));

        List<CollaborationMode> modes = hub.listModes();
        assertThat(modes).extracting(CollaborationMode::getModeId)
                .containsExactly("a", "b", "c");
    }

    // ========== findCompatible ==========

    @Nested
    @DisplayName("findCompatible")
    class FindCompatible {

        @Test
        @DisplayName("Exact topology match returns matching modes")
        void exactMatch() {
            hub.register(new FakeMode("m1", TopologyType.STAR));
            hub.register(new FakeMode("m2", TopologyType.MESH));

            List<CollaborationMode> result = hub.findCompatible(TopologyType.STAR);
            assertThat(result).extracting(CollaborationMode::getModeId).containsExactly("m1");
        }

        @Test
        @DisplayName("HYBRID modes are always compatible")
        void hybridAlwaysCompatible() {
            hub.register(new FakeMode("h1", TopologyType.HYBRID));
            hub.register(new FakeMode("s1", TopologyType.STAR));

            List<CollaborationMode> result = hub.findCompatible(TopologyType.MESH);
            assertThat(result).extracting(CollaborationMode::getModeId).containsExactly("h1");
        }

        @Test
        @DisplayName("Null topology returns empty list")
        void nullTopologyReturnsEmpty() {
            hub.register(new FakeMode("m1", TopologyType.STAR));
            assertThat(hub.findCompatible(null)).isEmpty();
        }
    }

    // ========== Fake mode ==========

    static class FakeMode implements CollaborationMode {
        private final String id;
        private final TopologyType topology;

        FakeMode(String id, TopologyType topology) {
            this.id = id;
            this.topology = topology;
        }

        @Override public String getModeId() { return id; }
        @Override public TopologyType getPreferredTopology() { return topology; }
        @Override public AssignmentPlan assign(List<AgentHandle> a, OrchestrationContext c) { return null; }
        @Override public CompletableFuture<AgentResult> execute(CollaborationContext c) { return null; }
        @Override public boolean cancel(String cid) { return false; }
        @Override public double getProgress(String cid) { return 0; }
        @Override public boolean supportsDynamicScaling() { return false; }
    }
}
