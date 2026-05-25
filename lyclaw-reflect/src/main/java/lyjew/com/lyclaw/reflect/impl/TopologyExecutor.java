package lyjew.com.lyclaw.reflect.impl;

import static lyjew.com.lyclaw.react.ContextKeys.RETRIEVAL_DECISION;
import static lyjew.com.lyclaw.react.ContextKeys.ROUTE_DECISION;
import static lyjew.com.lyclaw.react.SseEventTypes.MESSAGE;
import static lyjew.com.lyclaw.react.SseEventTypes.TOOL_APPROVAL;
import static lyjew.com.lyclaw.react.SseEventTypes.TOOL_CALL;

import lyjew.com.lyclaw.reflect.condition.AlwaysPassEvaluator;
import lyjew.com.lyclaw.reflect.condition.ConditionEvaluator;
import lyjew.com.lyclaw.reflect.condition.ConditionEvaluatorRegistry;
import lyjew.com.lyclaw.reflect.condition.ConsistencyEvaluator;
import lyjew.com.lyclaw.reflect.condition.EvaluationResultEvaluator;
import lyjew.com.lyclaw.reflect.condition.RetrievalDecisionEvaluator;
import lyjew.com.lyclaw.reflect.condition.RouteDecisionEvaluator;
import lyjew.com.lyclaw.reflect.condition.ScoreThresholdEvaluator;
import lyjew.com.lyclaw.reflect.impl.hook.MemoryHookRegistry;
import lyjew.com.lyclaw.reflect.model.*;
import lyjew.com.lyclaw.reflect.primitive.*;
import lyjew.com.lyclaw.reflect.registry.PrimitiveFactory;
import lyjew.com.lyclaw.reflect.topology.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 拓扑图解释器 — 遍历反射 DAG，按边条件匹配执行原语节点，是反思系统的核心执行引擎。
 *
 * <h3>执行策略</h3>
 * <ol>
 *   <li>从 entryNodeId 出发，在当前迭代内顺序遍历节点链直到出口节点</li>
 *   <li>每个节点通过 {@link PrimitiveFactory} 按 (PrimitiveType, implementationName) 解析原语实例</li>
 *   <li>Router 节点产出 {@link RouteDecision}，存储到 ctx.attributes["routeDecision"] 供边匹配使用</li>
 *   <li>边匹配优先级：Router 决策 → Evaluation 结果 → RetrievalDecision → ALWAYS 无条件边</li>
 *   <li>ON_RETRY 边将执行流回到上游节点时，visitedInIteration 检测到回路，iteration 计数器递增</li>
 *   <li>达到出口节点后执行 Synthesizer/Memory 等收尾原语，组装 {@link ExecutionResult} 返回</li>
 *   <li>maxIterations 作为安全阀防止无限循环，超限后 Router 的 FALLBACK 决策终止执行</li>
 * </ol>
 */
public class TopologyExecutor {

    private final PrimitiveFactory primitiveFactory;
    private final MemoryHookRegistry hookRegistry;
    private final ConditionEvaluatorRegistry conditionRegistry;
    private final Map<PrimitiveType, NodeExecutor> nodeExecutors = new EnumMap<>(PrimitiveType.class);

    public TopologyExecutor(PrimitiveFactory primitiveFactory) {
        this(primitiveFactory, null, defaultConditionRegistry());
    }

    public TopologyExecutor(PrimitiveFactory primitiveFactory, MemoryHookRegistry hookRegistry) {
        this(primitiveFactory, hookRegistry, defaultConditionRegistry());
    }

    private static ConditionEvaluatorRegistry defaultConditionRegistry() {
        return new ConditionEvaluatorRegistry(List.of(
                new RouteDecisionEvaluator(),
                new EvaluationResultEvaluator(),
                new ScoreThresholdEvaluator(),
                new ConsistencyEvaluator(),
                new RetrievalDecisionEvaluator(),
                new AlwaysPassEvaluator()
        ));
    }

    public TopologyExecutor(PrimitiveFactory primitiveFactory, MemoryHookRegistry hookRegistry,
                             ConditionEvaluatorRegistry conditionRegistry) {
        this.primitiveFactory = primitiveFactory;
        this.hookRegistry = hookRegistry;
        this.conditionRegistry = conditionRegistry;
        nodeExecutors.put(PrimitiveType.ACTOR, this::executeActor);
        nodeExecutors.put(PrimitiveType.EVALUATOR, this::executeEvaluator);
        nodeExecutors.put(PrimitiveType.REFLECTOR, this::executeReflector);
        nodeExecutors.put(PrimitiveType.ROUTER, this::executeRouter);
        nodeExecutors.put(PrimitiveType.SYNTHESIZER, this::executeSynthesizer);
        nodeExecutors.put(PrimitiveType.MEMORY, this::executeMemory);
        nodeExecutors.put(PrimitiveType.RETRIEVAL_GATE, this::executeRetrievalGate);
        nodeExecutors.put(PrimitiveType.COMPOSITE, this::executeComposite);
    }

    /** 节点执行策略函数式接口 */
    @FunctionalInterface
    private interface NodeExecutor {
        void execute(NodeDef node, ReflectionContext ctx, ReflectionTopology topology,
                     Consumer<TopologyEvent> eventSink);
    }

    // ── 公共入口 ──

    public ExecutionResult execute(ReflectionTopology topology, ReflectionContext ctx) {
        return execute(topology, ctx, event -> {});
    }

    public ExecutionResult execute(ReflectionTopology topology, ReflectionContext ctx,
                                    Consumer<TopologyEvent> eventSink) {
        long startMs = System.currentTimeMillis();
        int maxIterations = topology.getMaxIterations();
        int iteration = 1;
        String currentNodeId = topology.getEntryNodeId();
        Map<String, Long> nodeDurations = new LinkedHashMap<>();
        List<Double> scores = new ArrayList<>();

        TopologyLifecycle lifecycle = new TopologyLifecycle(hookRegistry, eventSink);
        lifecycle.emitTopologyStart(topology.getName(), maxIterations);

        while (iteration <= maxIterations && currentNodeId != null) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            ctx.setIteration(iteration);
            Set<String> visited = new HashSet<>();
            visited.add(currentNodeId);

            currentNodeId = traverseIteration(topology, ctx, eventSink,
                    nodeDurations, scores, visited, currentNodeId);

            if (currentNodeId != null && topology.getExitNodeIds().contains(currentNodeId)) {
                NodeDef exitNode = topology.getNodes().get(currentNodeId);
                if (exitNode != null) {
                    executeNode(exitNode, ctx, topology, eventSink, nodeDurations);
                }
                break;
            }

            if (currentNodeId != null) {
                iteration++;
                if (iteration <= maxIterations) {
                    lifecycle.emitIterationStart(iteration, maxIterations);
                }
            }
        }

        ExecutionResult result = buildResult(ctx, iteration, scores, nodeDurations, startMs);
        lifecycle.emitTopologyEnd(iteration, System.currentTimeMillis() - startMs);
        lifecycle.dispatchTopologyEnd(ctx, result);
        return result;
    }

    // ── 策略派发 ──

    private void executeNode(NodeDef node, ReflectionContext ctx, ReflectionTopology topology,
                             Consumer<TopologyEvent> eventSink, Map<String, Long> nodeDurations) {
        NodeExecutor executor = nodeExecutors.get(node.getPrimitiveType());
        if (executor == null) {
            throw new IllegalStateException("不支持的原语类型: " + node.getPrimitiveType());
        }
        String nodeId = node.getNodeId();
        int iter = ctx.getIteration();
        if (Thread.currentThread().isInterrupted()) {
            throw new RuntimeException("拓扑执行被取消: " + nodeId);
        }
        try {
            eventSink.accept(TopologyEvent.nodeStart(nodeId, node.getPrimitiveType().name(), iter));
            long start = System.currentTimeMillis();
            executor.execute(node, ctx, topology, eventSink);
            nodeDurations.merge(nodeId, System.currentTimeMillis() - start, Long::sum);
        } catch (Exception ex) {
            eventSink.accept(TopologyEvent.nodeError(nodeId,
                    ex.getMessage() != null ? ex.getMessage() : "未知错误", iter));
            throw ex;
        }
    }

    // ── 节点执行策略 ──

    private void executeActor(NodeDef node, ReflectionContext ctx, ReflectionTopology topology,
                              Consumer<TopologyEvent> eventSink) {
        String impl = node.getImplementationName();
        String nodeId = node.getNodeId();
        int iter = ctx.getIteration();
        long start = System.currentTimeMillis();

        Actor actor = primitiveFactory.resolve(PrimitiveType.ACTOR, impl);
        if (actor == null) throw new IllegalStateException("Actor 未注册: " + impl);

        ActorResult result = actor.executeStream(ctx, sse -> {
            String evt = sse.event();
            String payload = sse.data();
            if (payload == null) return;
            if (TOOL_CALL.equals(evt)) {
                eventSink.accept(TopologyEvent.actorChunk(nodeId, TOOL_CALL, payload, iter));
            } else if (TOOL_APPROVAL.equals(evt)) {
                eventSink.accept(TopologyEvent.actorChunk(nodeId, TOOL_APPROVAL, payload, iter));
            } else {
                eventSink.accept(TopologyEvent.actorChunk(nodeId,
                        evt != null ? evt : MESSAGE, payload, iter));
            }
        });
        ctx.setCurrentOutput(result.getOutput());
        ctx.addOutput(result.getOutput());
        if (result.getTaskSummary() != null) ctx.setTaskSummary(result.getTaskSummary());
        if (hookRegistry != null) hookRegistry.dispatchActorAfter(ctx, impl, result.getOutput());
        eventSink.accept(TopologyEvent.actorOutput(nodeId, result.getOutput(), iter,
                System.currentTimeMillis() - start));
    }

    private void executeEvaluator(NodeDef node, ReflectionContext ctx, ReflectionTopology topology,
                                  Consumer<TopologyEvent> eventSink) {
        String impl = node.getImplementationName();
        String nodeId = node.getNodeId();
        int iter = ctx.getIteration();
        long start = System.currentTimeMillis();

        Evaluator evaluator = primitiveFactory.resolve(PrimitiveType.EVALUATOR, impl);
        if (evaluator == null) throw new IllegalStateException("Evaluator 未注册: " + impl);

        Evaluation evaluation = evaluator.evaluateStream(ctx,
                chunk -> eventSink.accept(TopologyEvent.evaluatorChunk(nodeId, chunk, iter)));
        ctx.setLastEvaluation(evaluation);
        ctx.addEvaluation(evaluation);
        if (evaluation.getIssues() != null) ctx.setCurrentIssues(evaluation.getIssues());
        if (hookRegistry != null) hookRegistry.dispatchEvaluatorAfter(ctx, evaluation);
        eventSink.accept(TopologyEvent.evaluatorComplete(nodeId,
                evaluation.getScore(), evaluation.isSuccess(),
                serializeIssues(evaluation), iter,
                System.currentTimeMillis() - start));
    }

    private void executeReflector(NodeDef node, ReflectionContext ctx, ReflectionTopology topology,
                                  Consumer<TopologyEvent> eventSink) {
        String impl = node.getImplementationName();
        String nodeId = node.getNodeId();
        int iter = ctx.getIteration();
        long start = System.currentTimeMillis();

        Reflector reflector = primitiveFactory.resolve(PrimitiveType.REFLECTOR, impl);
        if (reflector == null) throw new IllegalStateException("Reflector 未注册: " + impl);

        String reflection = reflector.reflectStream(ctx, ctx.getLastEvaluation(),
                chunk -> eventSink.accept(TopologyEvent.reflectorChunk(nodeId, chunk, iter)));
        ctx.setCurrentReflection(reflection);
        ctx.addReflection(reflection);
        if (hookRegistry != null) hookRegistry.dispatchReflectorAfter(ctx, reflection);
        eventSink.accept(TopologyEvent.reflectorComplete(nodeId, reflection, iter,
                System.currentTimeMillis() - start));
    }

    private void executeRouter(NodeDef node, ReflectionContext ctx, ReflectionTopology topology,
                               Consumer<TopologyEvent> eventSink) {
        String impl = node.getImplementationName();
        String nodeId = node.getNodeId();
        int iter = ctx.getIteration();

        Router router = primitiveFactory.resolve(PrimitiveType.ROUTER, impl);
        if (router == null) throw new IllegalStateException("Router 未注册: " + impl);

        RouteDecision decision = router.routeStream(ctx, ctx.getLastEvaluation(),
                iter, topology.getMaxIterations(),
                chunk -> eventSink.accept(TopologyEvent.routerChunk(nodeId, chunk, iter)));
        ctx.setAttribute(ROUTE_DECISION, decision);
        if (hookRegistry != null) hookRegistry.dispatchRouterAfter(ctx, decision, iter);
        String reason = decision == RouteDecision.RETRY ? "评分未达标，继续反思"
                : decision == RouteDecision.STOP ? "质量达标，停止迭代"
                : decision == RouteDecision.FALLBACK ? "超过最大迭代次数，回退"
                : "继续";
        eventSink.accept(TopologyEvent.routerDecision(nodeId, decision.name(), reason, iter));
    }

    private void executeSynthesizer(NodeDef node, ReflectionContext ctx, ReflectionTopology topology,
                                    Consumer<TopologyEvent> eventSink) {
        String impl = node.getImplementationName();
        String nodeId = node.getNodeId();
        int iter = ctx.getIteration();
        long start = System.currentTimeMillis();

        Synthesizer synthesizer = primitiveFactory.resolve(PrimitiveType.SYNTHESIZER, impl);
        if (synthesizer == null) throw new IllegalStateException("Synthesizer 未注册: " + impl);

        String finalOutput = synthesizer.synthesizeStream(ctx, ctx.getOutputs(), ctx.getEvaluations(),
                chunk -> eventSink.accept(TopologyEvent.synthesizerChunk(nodeId, chunk, iter)));
        ctx.setFinalOutput(finalOutput);
        eventSink.accept(TopologyEvent.synthesisComplete(nodeId, finalOutput, iter,
                System.currentTimeMillis() - start));
    }

    private void executeMemory(NodeDef node, ReflectionContext ctx, ReflectionTopology topology,
                               Consumer<TopologyEvent> eventSink) {
        String impl = node.getImplementationName();
        String nodeId = node.getNodeId();
        int iter = ctx.getIteration();

        Memory memory = primitiveFactory.resolve(PrimitiveType.MEMORY, impl);
        if (memory != null) {
            MemoryRecord record = new MemoryRecord();
            record.setType(MemoryType.REFLECTION);
            record.setAgentId(ctx.getAgentId());
            record.setSessionId(ctx.getSessionId());
            record.setSummary(ctx.getTaskSummary());
            record.setContent(ctx.getCurrentOutput());
            memory.store(ctx, record);
            eventSink.accept(TopologyEvent.memoryStore(nodeId, ctx.getTaskSummary(), iter));
        }
    }

    private void executeRetrievalGate(NodeDef node, ReflectionContext ctx, ReflectionTopology topology,
                                      Consumer<TopologyEvent> eventSink) {
        String impl = node.getImplementationName();
        RetrievalGate gate = primitiveFactory.resolve(PrimitiveType.RETRIEVAL_GATE, impl);
        if (gate != null) {
            ctx.setAttribute(RETRIEVAL_DECISION, gate.decide(ctx));
        }
    }

    private void executeComposite(NodeDef node, ReflectionContext ctx, ReflectionTopology topology,
                                  Consumer<TopologyEvent> eventSink) {
        ReflectionTopology sub = node.getSubTopology();
        if (sub == null) throw new IllegalStateException("COMPOSITE 节点缺少子拓扑定义: " + node.getNodeId());
        ExecutionResult subResult = execute(sub, ctx, eventSink);
        if (subResult.getFinalOutput() != null) {
            ctx.setCurrentOutput(subResult.getFinalOutput());
            ctx.addOutput(subResult.getFinalOutput());
        }
    }

    // ── 内层遍历 ──

    /**
     * 沿DAG链遍历一整个迭代：从 startNodeId 出发，经过中间节点，
     * 直到抵达出口节点或检测到回路。
     * @return 出口节点ID（正常结束）、回路节点ID（需递增迭代）、或 null
     */
    private String traverseIteration(ReflectionTopology topology, ReflectionContext ctx,
                                      Consumer<TopologyEvent> eventSink,
                                      Map<String, Long> nodeDurations, List<Double> scores,
                                      Set<String> visited, String startNodeId) {
        String nodeId = startNodeId;
        while (nodeId != null && !topology.getExitNodeIds().contains(nodeId)) {
            NodeDef node = topology.getNodes().get(nodeId);
            if (node == null) throw new IllegalStateException("拓扑中未找到节点: " + nodeId);

            executeNode(node, ctx, topology, eventSink, nodeDurations);

            if (node.getPrimitiveType() == PrimitiveType.EVALUATOR && ctx.getLastEvaluation() != null) {
                scores.add(ctx.getLastEvaluation().getScore());
            }

            String next = resolveNextNode(topology, nodeId, ctx, eventSink);
            if (next != null && visited.contains(next)) {
                return next; // 回路：让外层递增迭代计数
            }
            if (next != null) visited.add(next);
            nodeId = next;
        }
        return nodeId;
    }

    // ── Fork/Join 并发执行 ──

    private static final ExecutorService FORK_POOL = Executors.newVirtualThreadPerTaskExecutor();

    /** 处理 FORK 边：并发执行所有分支，完成后查找 JOIN 汇聚节点 */
    private String handleFork(ReflectionTopology topology, String forkFromNodeId, Edge forkEdge,
                               ReflectionContext ctx, Consumer<TopologyEvent> eventSink) {
        List<String> branchTargets = forkEdge.getTo();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        List<ReflectionContext> branchCtxs = new ArrayList<>();
        long forkStart = System.currentTimeMillis();

        eventSink.accept(TopologyEvent.forkStart(forkFromNodeId, branchTargets.size(), ctx.getIteration()));

        for (String branchEntry : branchTargets) {
            ReflectionContext branchCtx = deepCopyContext(ctx);
            branchCtxs.add(branchCtx);
            futures.add(CompletableFuture.runAsync(() ->
                    executeBranch(topology, branchEntry, branchCtx, eventSink), FORK_POOL));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        mergeBranchResults(ctx, branchCtxs);

        for (Edge edge : topology.getEdges()) {
            if (edge.getEdgeType() == EdgeType.JOIN && edge.getFrom().contains(forkEdge.getFrom())) {
                String joinNodeId = edge.getSingleTo();
                eventSink.accept(TopologyEvent.joinComplete(joinNodeId, branchTargets.size(),
                        ctx.getIteration(), System.currentTimeMillis() - forkStart));
                return joinNodeId;
            }
        }
        return null;
    }

    /** 分支执行 */
    private void executeBranch(ReflectionTopology topology, String entryNodeId, ReflectionContext ctx,
                                Consumer<TopologyEvent> eventSink) {
        String nodeId = entryNodeId;
        Set<String> visited = new HashSet<>();
        int maxSteps = topology.getMaxIterations() * topology.getNodes().size();
        Map<String, Long> branchDurations = new LinkedHashMap<>();
        List<Double> branchScores = new ArrayList<>();

        for (int step = 0; step < maxSteps && nodeId != null; step++) {
            if (visited.contains(nodeId) || topology.getExitNodeIds().contains(nodeId)) break;
            visited.add(nodeId);

            NodeDef node = topology.getNodes().get(nodeId);
            if (node == null) break;
            executeNode(node, ctx, topology, eventSink, branchDurations);

            List<Edge> out = topology.outgoingEdges(nodeId);
            String next = null;
            for (Edge e : out) {
                if (e.getEdgeType() != EdgeType.FORK && e.getCondition() == EdgeCondition.ALWAYS) {
                    next = e.getSingleTo(); break;
                }
            }
            if (next == null) {
                for (Edge e : out) {
                    if (e.getEdgeType() != EdgeType.FORK) { next = e.getSingleTo(); break; }
                }
            }
            nodeId = next;
        }
    }

    // ── 边匹配 ──

    /**
     * 根据策略模式解析下一个节点：遍历出边，委托 {@link ConditionEvaluator} 匹配。
     * 边条件评估由 {@link ConditionEvaluatorRegistry} 中注册的策略实现处理，
     * 无需硬编码 switch。
     */
    private String resolveNextNode(ReflectionTopology topology, String fromNodeId, ReflectionContext ctx,
                                     Consumer<TopologyEvent> eventSink) {
        List<Edge> outgoing = topology.outgoingEdges(fromNodeId);
        if (outgoing.isEmpty()) return null;

        // FORK 边：并行分支
        for (Edge edge : outgoing) {
            if (edge.getEdgeType() == EdgeType.FORK) return handleFork(topology, fromNodeId, edge, ctx, eventSink);
        }

        // 委托策略注册表按边条件匹配
        for (Edge edge : outgoing) {
            ConditionEvaluator evaluator = conditionRegistry.get(edge.getCondition());
            if (evaluator != null && evaluator.matches(edge, ctx)) {
                return edge.getSingleTo();
            }
        }
        return null;
    }

    // ── 上下文工具 ──

    private void mergeBranchResults(ReflectionContext main, List<ReflectionContext> branches) {
        for (ReflectionContext branch : branches) {
            branch.getOutputs().forEach(main::addOutput);
            branch.getEvaluations().forEach(main::addEvaluation);
            branch.getReflections().forEach(main::addReflection);
            if (branch.getCurrentReflection() != null) main.setCurrentReflection(branch.getCurrentReflection());
            if (branch.getCurrentIssues() != null) main.setCurrentIssues(branch.getCurrentIssues());
        }
    }

    private ReflectionContext deepCopyContext(ReflectionContext src) {
        ReflectionContext copy = new ReflectionContext();
        copy.setAgentId(src.getAgentId());
        copy.setSessionId(src.getSessionId());
        copy.setUserId(src.getUserId());
        copy.setProjectId(src.getProjectId());
        copy.setUserMessage(src.getUserMessage());
        copy.setSystemPrompt(src.getSystemPrompt());
        copy.setCurrentOutput(src.getCurrentOutput());
        copy.setIteration(src.getIteration());
        copy.setCurrentReflection(src.getCurrentReflection());
        copy.setCurrentIssues(src.getCurrentIssues());
        src.getOutputs().forEach(copy::addOutput);
        src.getEvaluations().forEach(copy::addEvaluation);
        return copy;
    }

    private List<Map<String, String>> serializeIssues(Evaluation eval) {
        if (eval.getIssues() == null) return List.of();
        return eval.getIssues().stream().map(issue -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("description", issue.getDescription() != null ? issue.getDescription() : "");
            m.put("severity", issue.getSeverity() != null ? issue.getSeverity().name() : "INFO");
            return m;
        }).toList();
    }

    private ExecutionResult buildResult(ReflectionContext ctx, int iterations,
                                         List<Double> scores, Map<String, Long> nodeDurations,
                                         long startMs) {
        ExecutionResult result = new ExecutionResult();
        result.setFinalOutput(ctx.getFinalOutput() != null ? ctx.getFinalOutput() : ctx.getCurrentOutput());
        result.setTotalIterations(iterations);
        result.setScores(scores);
        result.setNodeDurations(nodeDurations);
        result.setTotalDurationMs(System.currentTimeMillis() - startMs);
        return result;
    }

    // ── 拓扑生命周期辅助类 ──

    private static class TopologyLifecycle {
        private final MemoryHookRegistry hookRegistry;
        private final Consumer<TopologyEvent> eventSink;

        TopologyLifecycle(MemoryHookRegistry hookRegistry, Consumer<TopologyEvent> eventSink) {
            this.hookRegistry = hookRegistry;
            this.eventSink = eventSink;
        }

        void emitTopologyStart(String name, int maxIterations) {
            eventSink.accept(TopologyEvent.topologyStart(name, maxIterations));
            eventSink.accept(TopologyEvent.iterationStart(1, maxIterations));
            if (hookRegistry != null) hookRegistry.dispatchTopologyStart(null, name);
        }

        void emitIterationStart(int iteration, int maxIterations) {
            eventSink.accept(TopologyEvent.iterationStart(iteration, maxIterations));
        }

        void emitTopologyEnd(int iterations, long totalDurationMs) {
            eventSink.accept(TopologyEvent.topologyEnd(Map.of(
                    "iterations", iterations,
                    "totalDurationMs", totalDurationMs)));
        }

        void dispatchTopologyEnd(ReflectionContext ctx, ExecutionResult result) {
            if (hookRegistry != null) hookRegistry.dispatchTopologyEnd(ctx, result);
        }
    }
}
