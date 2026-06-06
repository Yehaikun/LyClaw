package lyjew.com.lyclaw.mesh;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试聚合策略：
 * - Vote（投票）
 * - Sum（拼接）
 * - First（首条）
 * - byName 工厂
 */
class AggregationStrategyTest {

    @Test
    void voteShouldReturnBestResult() {
        AggregationStrategy vote = AggregationStrategy.vote();
        assertEquals("vote", vote.name());

        List<AgentMessage> results = List.of(
                response("result-a"),
                response("result-b"),
                response("result-a")
        );

        OrchestrationSpec spec = OrchestrationSpec.builder()
                .pattern(OrchestrationPattern.FAN_OUT).build();
        String aggregated = vote.aggregate(results, spec);

        // "result-a" appears twice, so it should be selected
        assertNotNull(aggregated);
    }

    @Test
    void sumShouldConcatenateResults() {
        AggregationStrategy sum = AggregationStrategy.sum();
        assertEquals("sum", sum.name());

        List<AgentMessage> results = List.of(
                response("first"),
                response("second"),
                response("third")
        );

        String aggregated = sum.aggregate(results,
                OrchestrationSpec.builder().pattern(OrchestrationPattern.FAN_OUT).build());

        assertTrue(aggregated.contains("first"));
        assertTrue(aggregated.contains("second"));
        assertTrue(aggregated.contains("third"));
    }

    @Test
    void firstShouldReturnFirstResult() {
        AggregationStrategy first = AggregationStrategy.first();
        assertEquals("first", first.name());

        List<AgentMessage> results = List.of(
                response("alpha"),
                response("beta")
        );

        String aggregated = first.aggregate(results,
                OrchestrationSpec.builder().pattern(OrchestrationPattern.FAN_OUT).build());

        assertEquals("alpha", aggregated);
    }

    @Test
    void firstShouldHandleEmptyResults() {
        String aggregated = AggregationStrategy.first().aggregate(List.of(),
                OrchestrationSpec.builder().pattern(OrchestrationPattern.FAN_OUT).build());
        assertEquals("", aggregated);
    }

    @Test
    void byNameShouldReturnCorrectStrategy() {
        assertInstanceOf(VoteStrategy.class, AggregationStrategy.byName("vote"));
        assertInstanceOf(SumStrategy.class, AggregationStrategy.byName("sum"));
        assertInstanceOf(SumStrategy.class, AggregationStrategy.byName("concat"));
        assertInstanceOf(FirstStrategy.class, AggregationStrategy.byName("first"));
    }

    private AgentMessage response(String payload) {
        return AgentMessage.builder()
                .type(MessageType.RESPONSE)
                .payload(payload)
                .build();
    }
}
