package lyjew.com.lyclaw.protocol.a2a;

import lyjew.com.lyclaw.dto.AgentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class A2aGatewayImplTest {

    private A2aGatewayImpl gateway;

    @BeforeEach
    void setUp() {
        gateway = new A2aGatewayImpl();
    }

    @Nested
    @DisplayName("getAgentCard")
    class GetAgentCard {

        @Test
        @DisplayName("Local agent returns cached card")
        void localAgentReturnsCached() throws Exception {
            A2aAgentCard card = A2aAgentCard.builder()
                    .agentId("local-1").name("LocalAgent").url("http://local")
                    .capabilities(List.of(AgentCapability.TEXT_GEN))
                    .build();
            gateway.registerLocalAgent(card);

            A2aAgentCard result = gateway.getAgentCard("local-1").get(5, TimeUnit.SECONDS);

            assertThat(result.getAgentId()).isEqualTo("local-1");
            assertThat(result.getName()).isEqualTo("LocalAgent");
        }

        @Test
        @DisplayName("Remote agent generates auto-discovery card")
        void remoteAgentGeneratesCard() throws Exception {
            A2aAgentCard result = gateway.getAgentCard("http://remote-agent.example.com")
                    .get(5, TimeUnit.SECONDS);

            assertThat(result.getAgentId()).startsWith("remote-");
            assertThat(result.getName()).contains("remote-agent");
            assertThat(result.getUrl()).isEqualTo("http://remote-agent.example.com");
            assertThat(result.getCapabilities()).contains(AgentCapability.TEXT_GEN);
        }
    }

    @Nested
    @DisplayName("sendTask")
    class SendTask {

        @Test
        @DisplayName("Send task returns completed result")
        void sendTaskReturnsCompleted() throws Exception {
            A2aTaskSpec task = A2aTaskSpec.builder()
                    .taskId("task-001").description("Test task")
                    .parameters(Map.of("key", "value"))
                    .build();

            AgentResult result = gateway.sendTask("http://agent.example.com", task)
                    .get(10, TimeUnit.SECONDS);

            assertThat(result.getStatus()).isEqualTo("COMPLETED");
            assertThat(result.getDetail()).contains("task-001");
        }

        @Test
        @DisplayName("Task without taskId generates one")
        void taskWithoutIdGeneratesOne() throws Exception {
            A2aTaskSpec task = A2aTaskSpec.builder()
                    .description("No ID task")
                    .build();

            AgentResult result = gateway.sendTask("http://agent.example.com", task)
                    .get(10, TimeUnit.SECONDS);

            assertThat(result.getStatus()).isEqualTo("COMPLETED");
            assertThat(result.getDetail()).contains("executed successfully");
        }

        @Test
        @DisplayName("Task status is tracked after sending")
        void taskStatusTracked() throws Exception {
            A2aTaskSpec task = A2aTaskSpec.builder()
                    .taskId("tracked-task").description("track me").build();

            gateway.sendTask("http://agent.example.com", task)
                    .get(10, TimeUnit.SECONDS);

            assertThat(gateway.getTaskStatus("tracked-task")).isEqualTo("COMPLETED");
        }
    }

    @Nested
    @DisplayName("cancelTask")
    class CancelTask {

        @Test
        @DisplayName("Cancel changes task status to CANCELLED")
        void cancelChangesStatus() {
            gateway.sendTask("http://agent.example.com",
                    A2aTaskSpec.builder().taskId("cancel-me").description("x").build());

            boolean result = gateway.cancelTask("http://agent.example.com", "cancel-me");

            assertThat(result).isTrue();
            assertThat(gateway.getTaskStatus("cancel-me")).isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("Cancel non-existent task still returns true")
        void cancelNonExistentReturnsTrue() {
            boolean result = gateway.cancelTask("http://x.com", "never-existed");
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("artifacts")
    class Artifacts {

        @Test
        @DisplayName("getArtifact returns cached artifact if cached")
        void returnsCachedArtifact() throws Exception {
            A2aArtifact artifact = A2aArtifact.builder()
                    .artifactId("art-1").taskId("task-1")
                    .content("cached content").mimeType("text/plain")
                    .build();
            gateway.cacheArtifact("task-1", artifact);

            A2aArtifact result = gateway.getArtifact("http://agent", "task-1", "art-1")
                    .get(5, TimeUnit.SECONDS);

            assertThat(result.getContent()).isEqualTo("cached content");
        }

        @Test
        @DisplayName("getArtifact generates artifact if not cached")
        void generatesArtifactIfNotCached() throws Exception {
            A2aArtifact result = gateway.getArtifact("http://agent", "task-1", "art-2")
                    .get(5, TimeUnit.SECONDS);

            assertThat(result.getArtifactId()).isEqualTo("art-2");
            assertThat(result.getTaskId()).isEqualTo("task-1");
            assertThat(result.getMimeType()).isEqualTo("text/plain");
        }
    }

    @Nested
    @DisplayName("registerLocalAgent")
    class RegisterLocalAgent {

        @Test
        @DisplayName("Agent with null agentId gets generated one")
        void nullAgentIdGenerates() {
            A2aAgentCard card = A2aAgentCard.builder()
                    .agentId(null).name("NoIdAgent").build();
            gateway.registerLocalAgent(card);

            // Uses the generated agentId as key
            assertThat(gateway.listLocalAgents()).isNotEmpty();
        }

        @Test
        @DisplayName("Lists all local agents")
        void listsAll() {
            gateway.registerLocalAgent(A2aAgentCard.builder().agentId("a1").build());
            gateway.registerLocalAgent(A2aAgentCard.builder().agentId("a2").build());

            assertThat(gateway.listLocalAgents()).hasSize(2);
        }
    }
}
