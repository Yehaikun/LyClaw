package lyjew.com.lyclaw.reflect.topology;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReflectionTopologyTest {

    @Test
    void shouldBuildPassthroughTopology() {
        var topo = ReflectionTopology.builder()
                .topologyId("passthrough")
                .name("passthrough")
                .actor("reActActor")
                .entryNode("actor-0")
                .exitNode("STOP")
                .edge("actor-0", "STOP", EdgeCondition.ALWAYS)
                .maxIterations(1)
                .build();

        assertEquals("passthrough", topo.getTopologyId());
        assertEquals(1, topo.getNodes().size());
        assertEquals(1, topo.getEdges().size());
        assertEquals("actor-0", topo.getEntryNodeId());
        assertTrue(topo.getExitNodeIds().contains("STOP"));
    }

    @Test
    void shouldBuildReflexionTopology() {
        var topo = ReflectionTopology.builder()
                .topologyId("reflexion")
                .actor("reActActor")
                .evaluator("llmJudgeEvaluator")
                .reflector("verbalReflector")
                .router("thresholdRouter")
                .entryNode("actor-0")
                .exitNode("STOP")
                .edge("actor-0", "evaluator-0", EdgeCondition.ALWAYS)
                .edge("evaluator-0", "router-0", EdgeCondition.ALWAYS)
                .edge("router-0", "reflector-0", EdgeCondition.ON_RETRY)
                .edge("reflector-0", "actor-0", EdgeCondition.ALWAYS)
                .edge("router-0", "STOP", EdgeCondition.ON_STOP)
                .maxIterations(3)
                .build();

        assertEquals(4, topo.getNodes().size());
        assertEquals(5, topo.getEdges().size());
    }

    @Test
    void shouldDetectCycleWithoutRouter() {
        var builder = ReflectionTopology.builder()
                .topologyId("bad")
                .actor("reActActor")
                .evaluator("llmJudgeEvaluator")
                .entryNode("actor-0")
                .edge("actor-0", "evaluator-0", EdgeCondition.ALWAYS)
                .edge("evaluator-0", "actor-0", EdgeCondition.ALWAYS)  // cycle but no router
                .maxIterations(3);

        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void shouldValidateMissingEntryNode() {
        var builder = ReflectionTopology.builder()
                .topologyId("bad")
                .actor("reActActor")
                .edge("actor-0", "STOP");

        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void shouldValidateDanglingEdge() {
        var builder = ReflectionTopology.builder()
                .topologyId("bad")
                .actor("reActActor")
                .entryNode("actor-0")
                .edge("actor-0", "nonexistent", EdgeCondition.ALWAYS);

        assertThrows(IllegalArgumentException.class, builder::build);
    }
}
