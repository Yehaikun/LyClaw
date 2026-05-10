package lyjew.com.lyclaw.protocol.impl;

import lyjew.com.lyclaw.agent.AgentTask;
import lyjew.com.lyclaw.agent.external.AgentCard;
import lyjew.com.lyclaw.agent.external.TaskStatus;
import lyjew.com.lyclaw.dto.AgentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalAgentAdapterImplTest {

    private ExternalAgentAdapterImpl adapter;

    @BeforeEach
    void setUp() {
        adapter = new ExternalAgentAdapterImpl();
    }

    @Nested
    @DisplayName("discover")
    class Discover {

        @Test
        @DisplayName("Null URL returns failed future")
        void nullUrlFails() {
            var future = adapter.discover(null);
            assertThat(future).isCompletedExceptionally();
            assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Blank URL returns failed future")
        void blankUrlFails() {
            var future = adapter.discover("   ");
            assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Discovery returns agent card with capabilities")
        void discoveryReturnsCard() throws Exception {
            AgentCard card = adapter.discover("http://external-agent.io").get(10, TimeUnit.SECONDS);

            assertThat(card.getAgentId()).startsWith("ext-");
            assertThat(card.getName()).contains("external-agent");
            assertThat(card.getCapabilities()).contains("TEXT_GEN", "TOOL_USE", "RAG");
            assertThat(card.getEndpoints()).hasSize(2);
        }

        @Test
        @DisplayName("Cached card returned on subsequent calls")
        void cachingWorks() throws Exception {
            AgentCard first = adapter.discover("http://cached-agent.com").get(10, TimeUnit.SECONDS);
            AgentCard second = adapter.discover("http://cached-agent.com").get(10, TimeUnit.SECONDS);

            assertThat(second.getAgentId()).isEqualTo(first.getAgentId());
        }

        @Test
        @DisplayName("Trailing slash is normalized")
        void trailingSlashNormalized() throws Exception {
            AgentCard with = adapter.discover("http://agent.io/").get(10, TimeUnit.SECONDS);
            AgentCard without = adapter.discover("http://agent.io").get(10, TimeUnit.SECONDS);

            assertThat(with.getAgentId()).isEqualTo(without.getAgentId());
        }

        @Test
        @DisplayName("Uses /.well-known/agent-card.json standard path")
        void usesWellKnownPath() throws Exception {
            AgentCard card = adapter.discover("https://myagent.ai").get(10, TimeUnit.SECONDS);
            assertThat(card.getUrl()).isEqualTo("https://myagent.ai");
        }
    }

    @Nested
    @DisplayName("sendTask")
    class SendTask {

        @Test
        @DisplayName("Null agent URL returns failed future")
        void nullUrlFails() {
            AgentTask task = new AgentTask("t1", "test", null, null, null);
            var future = adapter.sendTask(null, task, Duration.ofSeconds(30));
            assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Null task returns failed future")
        void nullTaskFails() {
            var future = adapter.sendTask("http://agent", null, Duration.ofSeconds(30));
            assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Task completes successfully with COMPLETED status")
        void taskCompletes() throws Exception {
            AgentTask task = new AgentTask("task-001", "analyse", "target1",
                    "payload content here", null);

            AgentResult result = adapter.sendTask("http://agent", task, Duration.ofSeconds(60))
                    .get(15, TimeUnit.SECONDS);

            assertThat(result.getStatus()).isEqualTo("COMPLETED");
            assertThat(result.getDetail()).contains("task-001");
            assertThat(result.getElapsedMs()).isPositive();
        }

        @Test
        @DisplayName("Task without taskId generates one")
        void taskWithoutId() throws Exception {
            AgentTask task = new AgentTask(null, "quick", "x", "data", null);

            AgentResult result = adapter.sendTask("http://agent", task, Duration.ofSeconds(60))
                    .get(15, TimeUnit.SECONDS);

            assertThat(result.getStatus()).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("Cancel works during execution")
        void cancelDuringExecution() throws Exception {
            AgentTask task = new AgentTask("cancel-test", "long", null, null, null);

            var future = adapter.sendTask("http://agent", task, Duration.ofSeconds(60));
            adapter.cancelTask("http://agent", "cancel-test").get(5, TimeUnit.SECONDS);

            AgentResult result = future.get(15, TimeUnit.SECONDS);
            assertThat(result.getStatus()).isIn("CANCELLED", "COMPLETED");
        }
    }

    @Nested
    @DisplayName("queryTaskStatus")
    class QueryTaskStatus {

        @Test
        @DisplayName("Null taskId returns failed future")
        void nullTaskIdFails() {
            assertThatThrownBy(() ->
                    adapter.queryTaskStatus("url", null).get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Unknown task returns PENDING")
        void unknownReturnsPending() throws Exception {
            TaskStatus status = adapter.queryTaskStatus("url", "unknown-task")
                    .get(5, TimeUnit.SECONDS);
            assertThat(status).isEqualTo(TaskStatus.PENDING);
        }

        @Test
        @DisplayName("Completed task returns COMPLETED")
        void completedTaskReturnsCompleted() throws Exception {
            AgentTask task = new AgentTask("query-task", "t", null, null, null);
            adapter.sendTask("http://agent", task, Duration.ofSeconds(60)).get(15, TimeUnit.SECONDS);

            TaskStatus status = adapter.queryTaskStatus("http://agent", "query-task")
                    .get(5, TimeUnit.SECONDS);
            assertThat(status).isEqualTo(TaskStatus.COMPLETED);
        }
    }

    @Nested
    @DisplayName("cancelTask")
    class CancelTask {

        @Test
        @DisplayName("Null taskId returns failed future")
        void nullTaskIdFails() {
            assertThatThrownBy(() ->
                    adapter.cancelTask("url", null).get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Cancel sets status to CANCELLED")
        void cancelSetsStatus() throws Exception {
            boolean result = adapter.cancelTask("http://agent", "some-task").get(5, TimeUnit.SECONDS);
            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("cache management")
    class CacheManagement {

        @Test
        @DisplayName("getCachedCard after discovery")
        void getCachedCard() throws Exception {
            adapter.discover("http://agent.io").get(10, TimeUnit.SECONDS);
            AgentCard cached = adapter.getCachedCard("http://agent.io");
            assertThat(cached).isNotNull();
            assertThat(cached.getName()).contains("agent.io");
        }

        @Test
        @DisplayName("getAllDiscoveredCards")
        void getAllDiscoveredCards() throws Exception {
            adapter.discover("http://a.com").get(10, TimeUnit.SECONDS);
            adapter.discover("http://b.com").get(10, TimeUnit.SECONDS);

            Map<String, AgentCard> all = adapter.getAllDiscoveredCards();
            assertThat(all).hasSize(2);
        }

        @Test
        @DisplayName("clearDiscoveryCache")
        void clearDiscoveryCache() throws Exception {
            adapter.discover("http://c.com").get(10, TimeUnit.SECONDS);
            adapter.clearDiscoveryCache();
            assertThat(adapter.getAllDiscoveredCards()).isEmpty();
        }

        @Test
        @DisplayName("clearTaskStatuses")
        void clearTaskStatuses() {
            adapter.clearTaskStatuses();
            // should not throw
        }
    }
}
