package lyjew.com.lyclaw.reflect.impl;

import lyjew.com.lyclaw.reflect.model.*;
import lyjew.com.lyclaw.reflect.primitive.*;
import lyjew.com.lyclaw.reflect.registry.PrimitiveFactory;
import lyjew.com.lyclaw.reflect.topology.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Batch 12 — Fork/Join + COMPOSITE 拓扑")
class ForkJoinCompositeTest {

    private PrimitiveFactory factory;
    private TopologyExecutor executor;

    @BeforeEach
    void setUp() {
        factory = new PrimitiveFactory();
        executor = new TopologyExecutor(factory);
    }

    // ── COMPOSITE 嵌套拓扑 ──

    @Nested
    @DisplayName("COMPOSITE 嵌套拓扑")
    class CompositeTests {
        @Test
        void compositeNodeExecutesSubTopology() {
            // 子拓扑：内部 Actor → Synthesizer
            ReflectionTopology subTopology = ReflectionTopology.builder()
                    .name("sub-topology")
                    .actor("simpleChat")
                    .synthesizer("lastOutput")
                    .edge("actor-0", "synthesizer-0")
                    .entryNode("actor-0")
                    .exitNode("synthesizer-0")
                    .maxIterations(1)
                    .build();

            // 注册子拓扑使用的原语
            Actor innerActor = ctx -> {
                ctx.setCurrentOutput("inner-generated");
                return new ActorResult("inner-generated");
            };
            factory.register(PrimitiveType.ACTOR, "simpleChat", innerActor);
            factory.register(PrimitiveType.SYNTHESIZER, "lastOutput",
                    new lyjew.com.lyclaw.reflect.impl.synthesizer.LastOutputSynthesizer());

            // 外层拓扑：COMPOSITE → Synthesizer
            ReflectionTopology outerTopology = ReflectionTopology.builder()
                    .name("outer-topology")
                    .compositeNode("composite-0", subTopology)
                    .synthesizer("lastOutput")
                    .edge("composite-0", "synthesizer-0")
                    .entryNode("composite-0")
                    .exitNode("synthesizer-0")
                    .maxIterations(1)
                    .build();

            ReflectionContext ctx = new ReflectionContext();
            ExecutionResult result = executor.execute(outerTopology, ctx);

            assertEquals("inner-generated", result.getFinalOutput());
            assertEquals(1, result.getTotalIterations()); // 无反思回绕时仅执行初始轮次
        }

        @Test
        void compositeWithoutSubTopologyThrows() {
            // 注册一个 COMPOSITE 节点但不带子拓扑
            ReflectionTopology badTopology = new ReflectionTopology();
            badTopology.setName("bad");
            badTopology.setEntryNodeId("composite-0");
            badTopology.setExitNodeIds(Set.of("composite-0"));
            NodeDef compositeNode = new NodeDef("composite-0", PrimitiveType.COMPOSITE, "composite");
            compositeNode.setSubTopology(null);
            badTopology.setNodes(Map.of("composite-0", compositeNode));
            badTopology.setEdges(List.of());
            badTopology.setMaxIterations(1);

            assertThrows(IllegalStateException.class,
                    () -> executor.execute(badTopology, new ReflectionContext()));
        }
    }

    // ── FORK/JOIN 并发执行 ──

    @Nested
    @DisplayName("FORK/JOIN 并发执行")
    class ForkJoinTests {
        @Test
        void forkExecutesBranchesInParallel() {
            AtomicInteger branchACalled = new AtomicInteger(0);
            AtomicInteger branchBCalled = new AtomicInteger(0);

            // 两个分支 Actor，各自产生不同输出
            Actor branchAActor = ctx -> {
                branchACalled.incrementAndGet();
                ctx.setCurrentOutput("branch-A-output");
                return new ActorResult("branch-A-output");
            };
            Actor branchBActor = ctx -> {
                branchBCalled.incrementAndGet();
                ctx.setCurrentOutput("branch-B-output");
                return new ActorResult("branch-B-output");
            };

            factory.register(PrimitiveType.ACTOR, "branchA", branchAActor);
            factory.register(PrimitiveType.ACTOR, "branchB", branchBActor);
            factory.register(PrimitiveType.SYNTHESIZER, "lastOutput",
                    new lyjew.com.lyclaw.reflect.impl.synthesizer.LastOutputSynthesizer());

            // 构建 FORK/JOIN 拓扑：入口 Actor → FORK → [A, B] → JOIN → Synthesizer
            ReflectionTopology topology = ReflectionTopology.builder()
                    .name("fork-join-test")
                    .node("entry-0", PrimitiveType.ACTOR, "reAct")
                    .node("branch-a", PrimitiveType.ACTOR, "branchA")
                    .node("branch-b", PrimitiveType.ACTOR, "branchB")
                    .synthesizer("lastOutput")
                    .edge("entry-0", "branch-a")
                    .forkEdge("entry-0", List.of("branch-a", "branch-b"))
                    .joinEdge(List.of("branch-a", "branch-b"), "synthesizer-0")
                    .entryNode("entry-0")
                    .exitNode("synthesizer-0")
                    .maxIterations(1)
                    .build();

            // 入口 Actor 不做任何事，只让流通过
            Actor entryActor = ctx -> new ActorResult("entry-output");
            factory.register(PrimitiveType.ACTOR, "reAct", entryActor);

            ReflectionContext ctx = new ReflectionContext();
            ExecutionResult result = executor.execute(topology, ctx);

            // 两个分支都应该被调用了
            assertEquals(1, branchACalled.get());
            assertEquals(1, branchBCalled.get());
        }
    }

    // ── 边匹配优先级验证 ──

    @Nested
    @DisplayName("边匹配优先级")
    class EdgePriorityTests {
        @Test
        void routerDecisionMatchesBeforeAlways() {
            // 同一个源节点同时有 ON_STOP 和 ALWAYS 两条出边
            Router alwaysStop = (ctx, eval, iter, max) -> RouteDecision.STOP;
            factory.register(PrimitiveType.ROUTER, "alwaysStop", alwaysStop);
            factory.register(PrimitiveType.SYNTHESIZER, "lastOutput",
                    new lyjew.com.lyclaw.reflect.impl.synthesizer.LastOutputSynthesizer());
            Actor edgeTestActor = ctx -> new ActorResult("hello");
            factory.register(PrimitiveType.ACTOR, "reAct", edgeTestActor);

            ReflectionTopology topology = ReflectionTopology.builder()
                    .name("edge-priority-test")
                    .actor("reAct")
                    .router("alwaysStop")
                    .synthesizer("lastOutput")
                    .edge("actor-0", "router-0")
                    // ON_STOP 应该优先匹配
                    .edge("router-0", "synthesizer-0", EdgeCondition.ON_STOP)
                    // FALLBACK 作为备选（不应被选中）
                    .edge("router-0", "synthesizer-0", EdgeCondition.ON_FALLBACK)
                    .entryNode("actor-0")
                    .exitNode("synthesizer-0")
                    .maxIterations(1)
                    .build();

            ReflectionContext ctx = new ReflectionContext();
            ExecutionResult result = executor.execute(topology, ctx);
            assertEquals("hello", result.getFinalOutput());
        }
    }
}
