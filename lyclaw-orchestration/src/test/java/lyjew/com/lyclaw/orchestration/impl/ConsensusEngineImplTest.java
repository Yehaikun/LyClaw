package lyjew.com.lyclaw.orchestration.impl;

import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.communication.ConsensusResult;
import lyjew.com.lyclaw.agent.communication.PeerResponse;
import lyjew.com.lyclaw.agent.communication.VoteResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ConsensusEngineImplTest {

    private ConsensusEngineImpl engine;

    @BeforeEach
    void setUp() {
        engine = new ConsensusEngineImpl();
    }

    // ========== hasConsensus ==========

    @Nested
    @DisplayName("hasConsensus")
    class HasConsensus {

        @Test
        @DisplayName("Single response should return true")
        void singleResponseReturnsTrue() {
            List<PeerResponse> responses = List.of(makeResponse("a1", "Agreed output for task"));
            assertThat(engine.hasConsensus(responses)).isTrue();
        }

        @Test
        @DisplayName("Null list returns false")
        void nullListReturnsFalse() {
            assertThat(engine.hasConsensus(null)).isFalse();
        }

        @Test
        @DisplayName("Empty list returns false")
        void emptyListReturnsFalse() {
            assertThat(engine.hasConsensus(List.of())).isFalse();
        }

        @Test
        @DisplayName("All agree on same content -> consensus")
        void allAgreeSameContent() {
            List<PeerResponse> responses = List.of(
                    makeResponse("a1", "The answer is 42"),
                    makeResponse("a2", "The answer is 42"),
                    makeResponse("a3", "The answer is 42"));
            assertThat(engine.hasConsensus(responses)).isTrue();
        }

        @Test
        @DisplayName("Content similar via Jaccard >= 0.5 -> consensus")
        void similarContentIsConsensus() {
            // 4 agents: a1/a2/a3 share core words, a4 different
            // Jaccard any pair of a1-a3 >= 3/5 = 0.6 >= 0.5
            // threshold = ceil(4*0.67) = 3; a1 agrees with a2,a3 -> 3 >= 3
            List<PeerResponse> responses = List.of(
                    makeResponse("a1", "system response success ok"),
                    makeResponse("a2", "system success response done"),
                    makeResponse("a3", "success system response good"),
                    makeResponse("a4", "completely unrelated text here"));
            assertThat(engine.hasConsensus(responses)).isTrue();
        }

        @Test
        @DisplayName("Completely different content -> no consensus")
        void differentContentNoConsensus() {
            List<PeerResponse> responses = List.of(
                    makeResponse("a1", "apple banana cherry"),
                    makeResponse("a2", "xylophone zebra yak"),
                    makeResponse("a3", "quantum physics electron"));
            assertThat(engine.hasConsensus(responses)).isFalse();
        }

        @Test
        @DisplayName("Majority agrees but below threshold -> no consensus")
        void belowThresholdNoConsensus() {
            // 3 responses, threshold = ceil(3*0.67) = 3, need all 3
            // if only 2 agree, no consensus
            List<PeerResponse> responses = List.of(
                    makeResponse("a1", "common result here"),
                    makeResponse("a2", "common result here"),
                    makeResponse("a3", "completely different output"));
            assertThat(engine.hasConsensus(responses)).isFalse();
        }

        @Test
        @DisplayName("5 responses, 4 agree -> consensus (80% >= 67%)")
        void fourOfFiveConsensus() {
            List<PeerResponse> responses = List.of(
                    makeResponse("a1", "same output"),
                    makeResponse("a2", "same output"),
                    makeResponse("a3", "same output"),
                    makeResponse("a4", "same output"),
                    makeResponse("a5", "different output"));
            // threshold = ceil(5*0.67) = 4, exactly 4 agree
            assertThat(engine.hasConsensus(responses)).isTrue();
        }

        @Test
        @DisplayName("Null content handled - both null -> agree")
        void bothNullContentsAgree() {
            List<PeerResponse> responses = List.of(
                    PeerResponse.builder().agentId("a1").content(null).build(),
                    PeerResponse.builder().agentId("a2").content(null).build());
            assertThat(engine.hasConsensus(responses)).isTrue();
        }
    }

    // ========== resolve ==========

    @Nested
    @DisplayName("resolve")
    class Resolve {

        @Test
        @DisplayName("Null responses returns no-consensus result")
        void nullResponsesReturnsNoConsensus() {
            ConsensusResult result = engine.resolve(null, 1);
            assertThat(result.isConsensusReached()).isFalse();
            assertThat(result.getAgreementRate()).isEqualTo(0.0);
            assertThat(result.getRoundsTaken()).isEqualTo(1);
        }

        @Test
        @DisplayName("Empty responses returns no-consensus result")
        void emptyResponsesReturnsNoConsensus() {
            ConsensusResult result = engine.resolve(List.of(), 2);
            assertThat(result.isConsensusReached()).isFalse();
            assertThat(result.getDecision()).isEqualTo("No responses available");
        }

        @Test
        @DisplayName("Consensus reached returns best response")
        void consensusReachedReturnsBest() {
            List<PeerResponse> responses = List.of(
                    makeResponse("a1", "same output", 0.9, 0.8, 0.7),
                    makeResponse("a2", "same output", 0.5, 0.5, 0.5),
                    makeResponse("a3", "same output", 0.6, 0.6, 0.6));

            ConsensusResult result = engine.resolve(responses, 3);

            assertThat(result.isConsensusReached()).isTrue();
            // a1 has highest weighted score
            assertThat(result.getMajorityAgentId()).isEqualTo("a1");
            assertThat(result.getRoundsTaken()).isEqualTo(3);
        }

        @Test
        @DisplayName("Agreement rate is correctly calculated")
        void agreementRateCorrect() {
            List<PeerResponse> responses = List.of(
                    makeResponse("a1", "output A", 0.9, 0.8, 0.7),
                    makeResponse("a2", "output A", 0.5, 0.5, 0.5),
                    makeResponse("a3", "completely different", 0.6, 0.6, 0.6));

            ConsensusResult result = engine.resolve(responses, 1);

            // 2 out of 3 agree with best (a1 has highest score, output=A)
            assertThat(result.getAgreementRate()).isCloseTo(2.0 / 3.0, within(0.001));
        }
    }

    // ========== vote ==========

    @Nested
    @DisplayName("vote")
    class Vote {

        @Test
        @DisplayName("Null candidates returns empty vote result")
        void nullCandidatesReturnsEmpty() {
            VoteResult result = engine.vote(null, List.of());
            assertThat(result.getWinnerAgentId()).isEqualTo("none");
            assertThat(result.getTotalVoters()).isEqualTo(0);
        }

        @Test
        @DisplayName("Empty candidates returns empty vote result")
        void emptyCandidatesReturnsEmpty() {
            VoteResult result = engine.vote(List.of(), List.of());
            assertThat(result.getWinnerAgentId()).isEqualTo("none");
        }

        @Test
        @DisplayName("Highest weighted score wins")
        void highestScoreWins() {
            List<PeerResponse> candidates = List.of(
                    PeerResponse.builder().agentId("a1").capabilityWeight(0.8)
                            .historicalAccuracy(0.9).confidence(0.7).content("r1").build(),
                    PeerResponse.builder().agentId("a2").capabilityWeight(0.9)
                            .historicalAccuracy(0.9).confidence(0.9).content("r2").build(),
                    PeerResponse.builder().agentId("a3").capabilityWeight(0.5)
                            .historicalAccuracy(0.5).confidence(0.5).content("r3").build());

            VoteResult result = engine.vote(candidates, null);

            // a2: 0.9*0.4 + 0.9*0.35 + 0.9*0.25 = 0.36 + 0.315 + 0.225 = 0.90
            // a1: 0.8*0.4 + 0.9*0.35 + 0.7*0.25 = 0.32 + 0.315 + 0.175 = 0.81
            // a3: 0.5*0.4 + 0.5*0.35 + 0.5*0.25 = 0.20 + 0.175 + 0.125 = 0.50
            assertThat(result.getWinnerAgentId()).isEqualTo("a2");
            assertThat(result.getWinnerScore()).isCloseTo(0.90, within(0.001));
        }

        @Test
        @DisplayName("Voter handle accuracy overrides candidate accuracy")
        void voterHandleAccuracyOverrides() {
            List<PeerResponse> candidates = List.of(
                    PeerResponse.builder().agentId("a1").capabilityWeight(0.8)
                            .historicalAccuracy(0.3).confidence(0.8).build());

            AgentHandle voter = AgentHandle.builder()
                    .agentId("a1").historicalAccuracy(0.95).build();

            VoteResult result = engine.vote(candidates, List.of(voter));

            // Uses voter accuracy 0.95 instead of 0.3
            double expected = 0.8 * 0.4 + 0.95 * 0.35 + 0.8 * 0.25;
            assertThat(result.getWinnerScore()).isCloseTo(expected, within(0.001));
        }

        @Test
        @DisplayName("Vote distribution is sorted descending")
        void voteDistributionSorted() {
            List<PeerResponse> candidates = List.of(
                    PeerResponse.builder().agentId("lo").capabilityWeight(0.3)
                            .historicalAccuracy(0.3).confidence(0.3).build(),
                    PeerResponse.builder().agentId("hi").capabilityWeight(0.9)
                            .historicalAccuracy(0.9).confidence(0.9).build(),
                    PeerResponse.builder().agentId("mid").capabilityWeight(0.5)
                            .historicalAccuracy(0.5).confidence(0.5).build());

            VoteResult result = engine.vote(candidates, null);

            // First entry should be "hi" (highest score)
            var it = result.getVoteDistribution().entrySet().iterator();
            assertThat(it.next().getKey()).isEqualTo("hi");
            assertThat(it.next().getKey()).isEqualTo("mid");
            assertThat(it.next().getKey()).isEqualTo("lo");
        }
    }

    // -- helpers --

    private PeerResponse makeResponse(String agentId, String content) {
        return PeerResponse.builder()
                .agentId(agentId).content(content)
                .capabilityWeight(0.8).historicalAccuracy(0.8).confidence(0.8)
                .build();
    }

    private PeerResponse makeResponse(String agentId, String content,
                                      double capability, double accuracy, double confidence) {
        return PeerResponse.builder()
                .agentId(agentId).content(content)
                .capabilityWeight(capability).historicalAccuracy(accuracy).confidence(confidence)
                .build();
    }
}
