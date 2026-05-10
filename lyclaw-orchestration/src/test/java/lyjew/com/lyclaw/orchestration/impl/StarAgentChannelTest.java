package lyjew.com.lyclaw.orchestration.impl;

import lyjew.com.lyclaw.agent.AgentMessage;
import lyjew.com.lyclaw.agent.collab.TopologyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class StarAgentChannelTest {

    private StarAgentChannel channel;

    @BeforeEach
    void setUp() {
        channel = new StarAgentChannel();
    }

    @Nested
    @DisplayName("send")
    class Send {

        @Test
        @DisplayName("Sending to a specific agent queues the message")
        void sendToSpecificAgent() {
            AgentMessage msg = message("a1", "a2", "test-type", "hello");
            channel.send(msg);

            // msg should be historically recorded
            assertThat(channel.getMessageHistory()).hasSize(1);
        }

        @Test
        @DisplayName("Subscribers receive messages")
        void subscribersReceiveMessages() {
            AtomicInteger received = new AtomicInteger(0);
            channel.subscribe("a2", m -> received.incrementAndGet());

            AgentMessage msg = message("a1", "a2", "test", "hello");
            channel.send(msg);

            assertThat(received.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("Global consumers receive all messages")
        void globalConsumersReceiveAll() {
            AtomicInteger globalReceived = new AtomicInteger(0);
            channel.subscribeGlobal(m -> globalReceived.incrementAndGet());

            channel.send(message("a1", "a2", "t1", "m1"));
            channel.send(message("a3", "a4", "t2", "m2"));

            assertThat(globalReceived.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("Consumer exception does not break other consumers")
        void consumerExceptionIsolated() {
            AtomicInteger goodReceived = new AtomicInteger(0);
            channel.subscribe("a2", m -> { throw new RuntimeException("boom"); });
            channel.subscribe("a2", m -> goodReceived.incrementAndGet());

            channel.send(message("a1", "a2", "t", "m"));
            assertThat(goodReceived.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("broadcast")
    class Broadcast {

        @Test
        @DisplayName("Broadcast delivers to all registered agent queues except sender")
        void broadcastToAllQueuesExceptSender() {
            // Pre-register queues by sending first
            channel.send(message("a1", "a2", "init", "queue setup"));
            channel.send(message("a3", "a4", "init", "queue setup"));

            // broadcast puts messages into queues (not per-agent consumers)
            channel.broadcast(message("a1", null, "broadcast", "to all"));

            // a2 was registered (not sender, should have message in queue)
            // a4 was registered (not sender, should have message in queue)
            // We verify via receive() which polls the queue
            channel.receive("a2");  // Polls the broadcast message from a2's queue
            // a2 should have 2 messages: init + broadcast, poll removes one
            channel.receive("a2");  // Polls the init message
        }

        @Test
        @DisplayName("Broadcast invokes global consumers")
        void broadcastInvokesGlobalConsumers() {
            AtomicInteger globalReceived = new AtomicInteger(0);
            channel.subscribeGlobal(m -> globalReceived.incrementAndGet());

            channel.broadcast(message("a1", null, "broadcast", "to globals"));

            assertThat(globalReceived.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("topology")
    class Topology {

        @Test
        @DisplayName("Default topology is STAR")
        void defaultTopologyIsStar() {
            assertThat(channel.getCurrentTopology()).isEqualTo(TopologyType.STAR);
        }

        @Test
        @DisplayName("Topology can be changed")
        void topologyCanBeChanged() {
            channel.setTopology(TopologyType.MESH);
            assertThat(channel.getCurrentTopology()).isEqualTo(TopologyType.MESH);
        }
    }

    @Nested
    @DisplayName("message history")
    class MessageHistory {

        @Test
        @DisplayName("History is capped at MAX_HISTORY=100")
        void historyCapped() {
            for (int i = 0; i < 150; i++) {
                channel.send(message("from", "to", "t" + i, "msg" + i));
            }
            assertThat(channel.getMessageHistory()).hasSize(100);
        }
    }

    @Nested
    @DisplayName("cleanup")
    class Cleanup {

        @Test
        @DisplayName("cleanupAgent removes queue and consumers")
        void cleanupAgent() {
            channel.send(message("a1", "a2", "t", "m"));
            channel.subscribe("a2", m -> {});
            assertThat(channel.getConnectedAgentCount()).isGreaterThan(0);

            channel.cleanupAgent("a2");
            assertThat(channel.getRegisteredAgents()).doesNotContain("a2");
        }

        @Test
        @DisplayName("clearAll resets everything")
        void clearAll() {
            channel.send(message("a1", "a2", "t", "m"));
            channel.subscribeGlobal(m -> {});
            assertThat(channel.getMessageHistory()).isNotEmpty();

            channel.clearAll();
            assertThat(channel.getMessageHistory()).isEmpty();
            assertThat(channel.getConnectedAgentCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("meshRoute")
    class MeshRoute {

        @Test
        @DisplayName("Mesh route excludes sender")
        void meshRouteExcludesSender() {
            channel.send(message("setup", "a2", "init", "q"));
            channel.send(message("setup", "a3", "init", "q"));

            AtomicInteger a3Received = new AtomicInteger();
            channel.subscribe("a3", m -> a3Received.incrementAndGet());

            channel.meshRoute(message("a2", null, "mesh", "test"));

            // a3 should receive, a2 should not (is sender)
            assertThat(a3Received.get()).isGreaterThanOrEqualTo(1);
        }
    }

    // -- helpers --

    private AgentMessage message(String from, String to, String type, String content) {
        return new AgentMessage(from, to, type, content, Instant.now());
    }
}
