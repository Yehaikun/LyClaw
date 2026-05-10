package lyjew.com.lyclaw.protocol.a2a;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aDiscoveryTest {

    private A2aDiscovery discovery;

    @BeforeEach
    void setUp() {
        discovery = new A2aDiscovery();
    }

    @Nested
    @DisplayName("discover")
    class Discover {

        @Test
        @DisplayName("Null URL throws IllegalArgumentException")
        void nullUrlThrows() {
            assertThatThrownBy(() -> discovery.discover(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null");
        }

        @Test
        @DisplayName("Blank URL throws IllegalArgumentException")
        void blankUrlThrows() {
            assertThatThrownBy(() -> discovery.discover("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be null");
        }

        @Test
        @DisplayName("Discovery registers agent and returns card")
        void discoveryReturnsCard() throws Exception {
            A2aAgentCard card = discovery.discover("http://agent1.example.com").get();

            assertThat(card).isNotNull();
            assertThat(card.getAgentId()).startsWith("discovered-");
            assertThat(card.getName()).contains("agent1.example.com");
            assertThat(card.getCapabilities()).contains(
                    AgentCapability.TEXT_GEN, AgentCapability.TOOL_USE, AgentCapability.RAG);
        }

        @Test
        @DisplayName("Caching: second discovery returns cached card")
        void cachingSecondReturnsCached() throws Exception {
            A2aAgentCard first = discovery.discover("http://agent2.example.com").get();
            A2aAgentCard second = discovery.discover("http://agent2.example.com").get();

            assertThat(second.getAgentId()).isEqualTo(first.getAgentId());
            assertThat(second.getName()).isEqualTo(first.getName());
        }

        @Test
        @DisplayName("Trailing slash is normalized")
        void trailingSlashNormalized() throws Exception {
            A2aAgentCard with = discovery.discover("http://agent3.example.com/").get();
            A2aAgentCard without = discovery.discover("http://agent3.example.com").get();

            assertThat(with.getAgentId()).isEqualTo(without.getAgentId());
        }

        @Test
        @DisplayName("Uses /.well-known/agent-card.json standard path")
        void usesWellKnownPath() throws Exception {
            A2aAgentCard card = discovery.discover("https://myagent.ai").get();

            assertThat(card.getUrl()).isEqualTo("https://myagent.ai");
            assertThat(card.getMetadata().get("discoveryMethod")).isEqualTo("well-known");
        }
    }

    @Nested
    @DisplayName("registerAgent")
    class RegisterAgent {

        @Test
        @DisplayName("Null card throws IllegalArgumentException")
        void nullCardThrows() {
            assertThatThrownBy(() -> discovery.registerAgent(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Card with null agentId throws")
        void nullAgentIdThrows() {
            A2aAgentCard card = A2aAgentCard.builder().agentId(null).build();
            assertThatThrownBy(() -> discovery.registerAgent(card))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Card with blank agentId throws")
        void blankAgentIdThrows() {
            A2aAgentCard card = A2aAgentCard.builder().agentId("   ").build();
            assertThatThrownBy(() -> discovery.registerAgent(card))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Valid registration succeeds")
        void validRegistrationSucceeds() {
            A2aAgentCard card = A2aAgentCard.builder()
                    .agentId("agent-001").name("Test Agent")
                    .url("http://example.com")
                    .capabilities(List.of(AgentCapability.TEXT_GEN))
                    .build();

            discovery.registerAgent(card);
            assertThat(discovery.getAgentCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("lookup")
    class Lookup {

        @Test
        @DisplayName("findAgent returns registered agent")
        void findAgent() {
            A2aAgentCard card = A2aAgentCard.builder()
                    .agentId("agent-001").name("Found").build();
            discovery.registerAgent(card);

            assertThat(discovery.findAgent("agent-001")).isEqualTo(card);
        }

        @Test
        @DisplayName("findAgent returns null for unknown")
        void findUnknown() {
            assertThat(discovery.findAgent("unknown")).isNull();
        }

        @Test
        @DisplayName("getDiscoveredAgents returns copy")
        void getDiscoveredAgents() {
            A2aAgentCard card = A2aAgentCard.builder()
                    .agentId("a1").name("Agent1").build();
            discovery.registerAgent(card);

            Map<String, A2aAgentCard> agents = discovery.getDiscoveredAgents();
            assertThat(agents).hasSize(1);
        }

        @Test
        @DisplayName("findAgentsByCapability filters correctly")
        void findAgentsByCapability() {
            discovery.registerAgent(A2aAgentCard.builder()
                    .agentId("a1").capabilities(List.of(AgentCapability.TEXT_GEN)).build());
            discovery.registerAgent(A2aAgentCard.builder()
                    .agentId("a2").capabilities(List.of(AgentCapability.TOOL_USE)).build());

            List<A2aAgentCard> textGen = discovery.findAgentsByCapability(AgentCapability.TEXT_GEN);
            assertThat(textGen).extracting(A2aAgentCard::getAgentId).containsExactly("a1");
        }
    }

    @Test
    @DisplayName("removeAgent works correctly")
    void removeAgent() {
        A2aAgentCard card = A2aAgentCard.builder()
                .agentId("a1").name("Agent1").url("http://x.com").build();
        discovery.registerAgent(card);
        assertThat(discovery.getAgentCount()).isEqualTo(1);

        discovery.removeAgent("a1");
        assertThat(discovery.getAgentCount()).isEqualTo(0);
    }
}
