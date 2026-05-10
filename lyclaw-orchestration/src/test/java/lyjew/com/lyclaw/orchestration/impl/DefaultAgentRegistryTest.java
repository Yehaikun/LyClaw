package lyjew.com.lyclaw.orchestration.impl;

import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.AgentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAgentRegistryTest {

    private DefaultAgentRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultAgentRegistry();
    }

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        void registerNullAgentSkipped() {
            registry.register(null);
            assertThat(registry.size()).isEqualTo(0);
        }

        @Test
        void registerAgentWithNullIdSkipped() {
            registry.register(AgentHandle.builder().agentId(null).build());
            assertThat(registry.size()).isEqualTo(0);
        }

        @Test
        void registerValidAgentSucceeds() {
            AgentHandle agent = AgentHandle.builder().agentId("a1").name("Agent1")
                    .state(AgentState.IDLE).capabilities(List.of("coding")).build();
            registry.register(agent);
            assertThat(registry.size()).isEqualTo(1);
        }

        @Test
        void replaceExistingAgent() {
            AgentHandle old = AgentHandle.builder().agentId("a1").name("Old")
                    .state(AgentState.IDLE).build();
            AgentHandle newer = AgentHandle.builder().agentId("a1").name("New")
                    .state(AgentState.RUNNING).build();
            registry.register(old);
            registry.register(newer);

            Optional<AgentHandle> found = registry.lookup("a1");
            assertThat(found).isPresent();
            assertThat(found.get().getName()).isEqualTo("New");
            assertThat(found.get().getState()).isEqualTo(AgentState.RUNNING);
        }
    }

    @Nested
    @DisplayName("lookup")
    class Lookup {

        @Test
        void lookupExistingAgent() {
            AgentHandle agent = AgentHandle.builder().agentId("a1").build();
            registry.register(agent);
            assertThat(registry.lookup("a1")).isPresent();
        }

        @Test
        void lookupNonExistentReturnsEmpty() {
            assertThat(registry.lookup("unknown")).isEmpty();
        }

        @Test
        void lookupNullReturnsEmpty() {
            assertThat(registry.lookup(null)).isEmpty();
        }

        @Test
        void lookupEmptyReturnsEmpty() {
            assertThat(registry.lookup("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByCapability")
    class FindByCapability {

        @BeforeEach
        void setupAgents() {
            registry.register(AgentHandle.builder().agentId("a1").capabilities(
                    List.of("coding", "writing")).createdAt(100L).build());
            registry.register(AgentHandle.builder().agentId("a2").capabilities(
                    List.of("coding")).createdAt(200L).build());
            registry.register(AgentHandle.builder().agentId("a3").capabilities(
                    List.of("writing")).createdAt(300L).build());
        }

        @Test
        void findByCapabilityIgnoreCase() {
            List<AgentHandle> result = registry.findByCapability("CODING");
            assertThat(result).extracting(AgentHandle::getAgentId)
                    .containsExactly("a1", "a2");
        }

        @Test
        void findByCapabilitySortedByCreatedAt() {
            List<AgentHandle> result = registry.findByCapability("coding");
            assertThat(result.get(0).getCreatedAt()).isLessThan(result.get(1).getCreatedAt());
        }

        @Test
        void nullCapabilityReturnsEmpty() {
            assertThat(registry.findByCapability(null)).isEmpty();
        }

        @Test
        void emptyCapabilityReturnsEmpty() {
            assertThat(registry.findByCapability("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByState")
    class FindByState {

        @BeforeEach
        void setupAgents() {
            registry.register(AgentHandle.builder().agentId("a1").state(AgentState.IDLE).createdAt(100L).build());
            registry.register(AgentHandle.builder().agentId("a2").state(AgentState.RUNNING).createdAt(200L).build());
            registry.register(AgentHandle.builder().agentId("a3").state(AgentState.IDLE).createdAt(300L).build());
        }

        @Test
        void findByStateReturnsMatches() {
            List<AgentHandle> idle = registry.findByState(AgentState.IDLE);
            assertThat(idle).extracting(AgentHandle::getAgentId).containsExactly("a1", "a3");

            List<AgentHandle> running = registry.findByState(AgentState.RUNNING);
            assertThat(running).extracting(AgentHandle::getAgentId).containsExactly("a2");
        }

        @Test
        void nullStateReturnsEmpty() {
            assertThat(registry.findByState(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("findAvailable")
    class FindAvailable {

        @BeforeEach
        void setupAgents() {
            registry.register(AgentHandle.builder().agentId("a1")
                    .state(AgentState.IDLE).capabilities(List.of("coding", "writing"))
                    .historicalAccuracy(0.8).createdAt(100L).build());
            registry.register(AgentHandle.builder().agentId("a2")
                    .state(AgentState.IDLE).capabilities(List.of("coding"))
                    .historicalAccuracy(0.9).createdAt(200L).build());
            registry.register(AgentHandle.builder().agentId("a3")
                    .state(AgentState.RUNNING).capabilities(List.of("coding", "writing"))
                    .historicalAccuracy(0.7).createdAt(300L).build());
            registry.register(AgentHandle.builder().agentId("a4")
                    .state(AgentState.IDLE).capabilities(List.of("debugging"))
                    .historicalAccuracy(0.5).createdAt(400L).build());
        }

        @Test
        void findAllIdleWhenNoCapabilities() {
            List<AgentHandle> result = registry.findAvailable(null);
            assertThat(result).extracting(AgentHandle::getAgentId)
                    .containsExactlyInAnyOrder("a1", "a2", "a4");
        }

        @Test
        void findAllIdleWhenEmptyCapabilities() {
            List<AgentHandle> result = registry.findAvailable(List.of());
            assertThat(result).extracting(AgentHandle::getAgentId)
                    .containsExactlyInAnyOrder("a1", "a2", "a4");
        }

        @Test
        void findIdleWithRequiredCapabilities() {
            List<AgentHandle> result = registry.findAvailable(List.of("coding", "writing"));
            assertThat(result).extracting(AgentHandle::getAgentId).containsExactly("a1");
        }

        @Test
        void sortedFromFindByState() {
            // findAvailable(null) delegates to findByState() which sorts by createdAt, not accuracy
            List<AgentHandle> result = registry.findAvailable(null);
            assertThat(result).extracting(AgentHandle::getAgentId)
                    .containsExactly("a1", "a2", "a4");
        }

        @Test
        void agentWithNoCapabilitiesExcluded() {
            registry.register(AgentHandle.builder().agentId("a5").state(AgentState.IDLE)
                    .capabilities(null).historicalAccuracy(0.9).build());
            registry.register(AgentHandle.builder().agentId("a6").state(AgentState.IDLE)
                    .capabilities(List.of()).historicalAccuracy(0.9).build());

            List<AgentHandle> result = registry.findAvailable(List.of("coding"));
            assertThat(result).extracting(AgentHandle::getAgentId).doesNotContain("a5", "a6");
        }
    }

    @Test
    void unregisterRemovesAgent() {
        registry.register(AgentHandle.builder().agentId("a1").name("test").build());
        assertThat(registry.size()).isEqualTo(1);

        registry.unregister("a1");
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    void listAllReturnsCopy() {
        registry.register(AgentHandle.builder().agentId("a1").build());
        registry.register(AgentHandle.builder().agentId("a2").build());
        assertThat(registry.listAll()).hasSize(2);
    }

    @Test
    void getStateDistributionCountsStates() {
        registry.register(AgentHandle.builder().agentId("a1").state(AgentState.IDLE).build());
        registry.register(AgentHandle.builder().agentId("a2").state(AgentState.IDLE).build());
        registry.register(AgentHandle.builder().agentId("a3").state(AgentState.RUNNING).build());

        Map<AgentState, Long> dist = registry.getStateDistribution();
        assertThat(dist).containsEntry(AgentState.IDLE, 2L)
                .containsEntry(AgentState.RUNNING, 1L);
    }
}
