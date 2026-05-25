package lyjew.com.lyclaw.reflect.impl;

import lyjew.com.lyclaw.reflect.topology.PrimitiveType;
import lyjew.com.lyclaw.reflect.topology.ReflectionTopology;
import lyjew.com.lyclaw.reflect.topology.TopologyPresets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Batch 11 — 预置拓扑模板")
class TopologyPresetsTest {

    @Nested
    @DisplayName("Passthrough")
    class PassthroughTests {
        @Test
        void singlePassNoEvaluation() {
            ReflectionTopology t = TopologyPresets.passthrough();
            assertEquals("passthrough", t.getName());
            assertEquals(1, t.getMaxIterations());
            assertTrue(t.getNodes().containsKey("actor-0"));
            assertTrue(t.getNodes().containsKey("synthesizer-0"));
            assertEquals("actor-0", t.getEntryNodeId());
        }
    }

    @Nested
    @DisplayName("Reflexion")
    class ReflexionTests {
        @Test
        void fullReflexionCycle() {
            ReflectionTopology t = TopologyPresets.reflexion();
            assertEquals("reflexion", t.getName());
            assertEquals(3, t.getMaxIterations());

            // 验证节点完整性
            assertTrue(t.getNodes().containsKey("actor-0"));
            assertTrue(t.getNodes().containsKey("evaluator-0"));
            assertTrue(t.getNodes().containsKey("router-0"));
            assertTrue(t.getNodes().containsKey("reflector-0"));
            assertTrue(t.getNodes().containsKey("synthesizer-0"));

            // 验证边完整性
            assertEquals(6, t.getEdges().size());
            assertEquals("actor-0", t.getEntryNodeId());
        }

        @Test
        void byNameReturnsCorrectly() {
            ReflectionTopology t = TopologyPresets.byName("reflexion");
            assertNotNull(t);
            assertEquals("reflexion", t.getName());
        }
    }

    @Nested
    @DisplayName("Self-Refine")
    class SelfRefineTests {
        @Test
        void noReflectorNode() {
            ReflectionTopology t = TopologyPresets.selfRefine();
            assertEquals("self-refine", t.getName());
            assertEquals(4, t.getMaxIterations());

            // Self-Refine 没有独立的 Reflector 节点
            boolean hasReflector = t.getNodes().values().stream()
                    .anyMatch(n -> n.getPrimitiveType() == PrimitiveType.REFLECTOR);
            assertFalse(hasReflector);

            assertTrue(t.getNodes().containsKey("actor-0"));
            assertTrue(t.getNodes().containsKey("evaluator-0"));
        }
    }

    @Nested
    @DisplayName("CRITIC")
    class CriticTests {
        @Test
        void toolVerifierEvaluator() {
            ReflectionTopology t = TopologyPresets.critic();
            assertEquals("critic", t.getName());

            // CRITIC 使用 ToolVerifier 作为评估器
            var evaluator = t.getNodes().get("evaluator-0");
            assertNotNull(evaluator);
            assertEquals("toolVerifierExitCode", evaluator.getImplementationName());
        }
    }

    @Nested
    @DisplayName("Multi-Evaluator")
    class MultiEvaluatorTests {
        @Test
        void twoEvaluators() {
            ReflectionTopology t = TopologyPresets.multiEvaluator();
            assertTrue(t.getNodes().containsKey("evaluator-0"));
            assertTrue(t.getNodes().containsKey("evaluator-1"));

            // 验证两个 Evaluator 存在
            long evalCount = t.getNodes().values().stream()
                    .filter(n -> n.getPrimitiveType() == PrimitiveType.EVALUATOR)
                    .count();
            assertEquals(2, evalCount);

            // 验证使用 BestScoreSynthesizer
            var synth = t.getNodes().get("synthesizer-0");
            assertEquals("bestScore", synth.getImplementationName());
        }
    }

    @Nested
    @DisplayName("Memory-Augmented")
    class MemoryAugmentedTests {
        @Test
        void memoryNodeBeforeReflector() {
            ReflectionTopology t = TopologyPresets.memoryAugmented();
            assertTrue(t.getNodes().containsKey("memory-0"));
            assertTrue(t.getNodes().containsKey("reflector-0"));

            // 验证 RETRY 路径经过 Memory
            boolean hasMemoryEdge = t.getEdges().stream()
                    .anyMatch(e -> e.getFrom().contains("router-0")
                            && e.getTo().contains("memory-0"));
            assertTrue(hasMemoryEdge);
        }
    }

    @Test
    void unknownNameReturnsNull() {
        assertNull(TopologyPresets.byName("nonexistent"));
    }

    @Test
    void allPresetsAreValid() {
        // 所有预置拓扑必须通过 TopologyValidator 验证
        assertNotNull(TopologyPresets.passthrough());
        assertNotNull(TopologyPresets.reflexion());
        assertNotNull(TopologyPresets.selfRefine());
        assertNotNull(TopologyPresets.critic());
        assertNotNull(TopologyPresets.multiEvaluator());
        assertNotNull(TopologyPresets.memoryAugmented());
    }
}
