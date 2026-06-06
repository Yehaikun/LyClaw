package lyjew.com.lyclaw.mesh.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lyjew.com.lyclaw.mesh.AgentMessage;
import lyjew.com.lyclaw.mesh.AgentRef;
import lyjew.com.lyclaw.mesh.AggregationStrategy;
import lyjew.com.lyclaw.mesh.DagDefinition;
import lyjew.com.lyclaw.mesh.MessageType;
import lyjew.com.lyclaw.mesh.OrchestrationEngine;
import lyjew.com.lyclaw.mesh.OrchestrationEvent;
import lyjew.com.lyclaw.mesh.OrchestrationPattern;
import lyjew.com.lyclaw.mesh.OrchestrationResult;
import lyjew.com.lyclaw.mesh.OrchestrationSpec;
import lyjew.com.lyclaw.mesh.AgentMesh;
import reactor.core.publisher.Flux;

/**
 * 默认编排引擎 —— 实现全部 6 种内置编排模式。
 *
 * <p>用户可以通过注入自定义 {@link OrchestrationEngine} Bean 来覆盖此默认实现。
 * 每种模式作为一个独立方法实现，方便子类覆写特定模式。</p>
 *
 * <p>模式实现：
 * <ul>
 *   <li>{@link #executeSingle(OrchestrationSpec)} — 路由到最佳 Agent</li>
 *   <li>{@link #executeChain(OrchestrationSpec)} — A → B → C 流水线</li>
 *   <li>{@link #executeFanOut(OrchestrationSpec)} — 并行 → 聚合</li>
 *   <li>{@link #executeDebate(OrchestrationSpec)} — 多轮辩论 → 综合</li>
 *   <li>{@link #executeDag(OrchestrationSpec)} — DAG 依赖执行</li>
 *   <li>{@link #executeSupervisor(OrchestrationSpec)} — Supervisor 分解执行</li>
 * </ul>
 */
public class DefaultOrchestrationEngine implements OrchestrationEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultOrchestrationEngine.class);

    private final AgentMesh mesh;
    private final AggregationStrategy defaultAggregation;

    public DefaultOrchestrationEngine(AgentMesh mesh) {
        this.mesh = mesh;
        this.defaultAggregation = AggregationStrategy.vote();
    }

    @Override
    public String engineName() { return "default"; }

    @Override
    public OrchestrationResult execute(OrchestrationSpec spec) {
        long startTime = System.currentTimeMillis();
        log.info("[Orchestration] Starting {} pattern | task={} | timeout={}ms",
                spec.getPattern(), truncate(spec.getTask(), 50), spec.getTimeoutMs());

        OrchestrationResult result;
        try {
            result = switch (spec.getPattern()) {
                case SINGLE -> executeSingle(spec);
                case CHAIN -> executeChain(spec);
                case FAN_OUT -> executeFanOut(spec);
                case DEBATE -> executeDebate(spec);
                case DAG -> executeDag(spec);
                case SUPERVISOR -> executeSupervisor(spec);
            };
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("[Orchestration] {} failed after {}ms: {}", spec.getPattern(), elapsed, e.getMessage(), e);
            return OrchestrationResult.failure(e.getMessage(), spec.getPattern(), List.of());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[Orchestration] {} completed | success={} | duration={}ms | agentResults={}",
                spec.getPattern(), result.isSuccess(), elapsed, result.getAgentResults().size());
        return result;
    }

    // ════════════════════════════════════════════════════════════
    // SINGLE — 路由到最佳 Agent
    // ════════════════════════════════════════════════════════════

    /**
     * SINGLE 模式：将任务路由到最匹配的一个 Agent。
     *
     * <p>根据以下优先级选择目标：
     * <ol>
     *   <li>显式指定 {@code agentIds}（取第一个）</li>
     *   <li>按 {@code requiredCapabilities} 匹配</li>
     *   <li>默认路由到 orchestrator 或第一个可用 Agent</li>
     * </ol>
     */
    protected OrchestrationResult executeSingle(OrchestrationSpec spec) {
        String targetId = resolveAgentId(spec);
        if (targetId == null) {
            return OrchestrationResult.failure(
                    "No suitable agent found for SINGLE pattern", spec.getPattern(), List.of());
        }

        AgentMessage request = buildRequest(targetId, spec);
        long start = System.currentTimeMillis();

        AgentMessage response = mesh.send(request).join();

        OrchestrationResult.AgentResult agentResult = new OrchestrationResult.AgentResult(
                targetId, response.getType() != MessageType.ERROR, response.getPayload(),
                response.getType() == MessageType.ERROR ? response.getPayload() : null,
                System.currentTimeMillis() - start, Map.of());

        if (response.isError()) {
            return OrchestrationResult.failure(response.getPayload(),
                    OrchestrationPattern.SINGLE, List.of(agentResult));
        }

        return OrchestrationResult.success(response.getPayload(),
                OrchestrationPattern.SINGLE, List.of(agentResult),
                System.currentTimeMillis() - start);
    }

    // ════════════════════════════════════════════════════════════
    // CHAIN — A → B → C 流水线
    // ════════════════════════════════════════════════════════════

    /**
     * CHAIN 模式：将任务依次送给多个 Agent，前一个的输出作为后一个的输入。
     *
     * <p>如果任一环节失败，整个 CHAIN 中断并返回失败结果。
     * 默认使用 {@code agentIds} 列表中指定的顺序。</p>
     */
    protected OrchestrationResult executeChain(OrchestrationSpec spec) {
        List<String> agentIds = resolveAgentIds(spec);
        if (agentIds.isEmpty()) {
            return OrchestrationResult.failure(
                    "No agents specified for CHAIN pattern", spec.getPattern(), List.of());
        }

        String currentPayload = spec.getPayload() != null ? spec.getPayload() : spec.getTask();
        if (currentPayload == null) currentPayload = "";

        List<OrchestrationResult.AgentResult> agentResults = new ArrayList<>();
        long chainStart = System.currentTimeMillis();

        for (int i = 0; i < agentIds.size(); i++) {
            String agentId = agentIds.get(i);
            long stepStart = System.currentTimeMillis();
            log.info("[Chain] Step {}/{} → {} | payloadLen={}", i + 1, agentIds.size(), agentId,
                    currentPayload != null ? currentPayload.length() : 0);

            AgentMessage request = AgentMessage.builder()
                    .type(MessageType.REQUEST)
                    .to(agentId)
                    .payload(currentPayload)
                    .correlationId("chain-" + UUID.randomUUID().toString().substring(0, 8))
                    .ttlMs(spec.getTimeoutMs() / agentIds.size())
                    .build();

            AgentMessage response = mesh.send(request).join();

            boolean stepSuccess = response.getType() != MessageType.ERROR;
            agentResults.add(new OrchestrationResult.AgentResult(agentId, stepSuccess,
                    response.getPayload(),
                    stepSuccess ? null : response.getPayload(),
                    System.currentTimeMillis() - stepStart, Map.of("step", i)));

            if (!stepSuccess) {
                log.warn("[Chain] Step {} failed: {} → aborting chain", i + 1, agentId);
                return OrchestrationResult.failure(
                        "Chain aborted at step " + (i + 1) + " (" + agentId + "): "
                                + response.getPayload(),
                        OrchestrationPattern.CHAIN, agentResults);
            }

            currentPayload = response.getPayload();
        }

        return OrchestrationResult.success(currentPayload,
                OrchestrationPattern.CHAIN, agentResults,
                System.currentTimeMillis() - chainStart);
    }

    // ════════════════════════════════════════════════════════════
    // FAN_OUT — 并行 → 聚合
    // ════════════════════════════════════════════════════════════

    /**
     * FAN_OUT 模式：并行派发任务给所有匹配的 Agent，然后聚合结果。
     *
     * <p>通过 {@code requiredCapabilities} 或 {@code agentIds} 选择目标 Agent。
     * 聚合策略通过 {@code aggregationStrategy} 指定（vote/sum/first/llm）。</p>
     */
    protected OrchestrationResult executeFanOut(OrchestrationSpec spec) {
        List<String> agentIds = resolveAgentIds(spec);
        if (agentIds.isEmpty()) {
            return OrchestrationResult.failure(
                    "No agents found for FAN_OUT pattern", spec.getPattern(), List.of());
        }

        long fanOutStart = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString().substring(0, 16);

        // 并行发送请求
        List<CompletableFuture<AgentMessage>> futures = agentIds.stream()
                .map(agentId -> {
                    AgentMessage request = buildRequest(agentId, spec);
                    if (request.getTraceId() == null) {
                        request = AgentMessage.builder()
                                .type(request.getType()).to(request.getTo())
                                .from(request.getFrom()).correlationId(request.getCorrelationId())
                                .traceId(traceId).payload(request.getPayload())
                                .ttlMs(spec.getTimeoutMs()).build();
                    }
                    return mesh.send(request);
                })
                .toList();

        // 等待所有结果
        List<AgentMessage> responses;
        try {
            if (spec.isWaitForAll()) {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(spec.getTimeoutMs(), TimeUnit.MILLISECONDS);
            }
            responses = futures.stream()
                    .map(f -> {
                        try { return f.get(spec.getTimeoutMs() / agentIds.size(), TimeUnit.MILLISECONDS); }
                        catch (Exception e) { return AgentMessage.builder()
                                .type(MessageType.ERROR).payload("Timeout: " + e.getMessage()).build(); }
                    })
                    .toList();
        } catch (Exception e) {
            log.warn("[FanOut] Timeout or error: {}", e.getMessage());
            responses = futures.stream()
                    .map(f -> {
                        try { return f.getNow(AgentMessage.builder()
                                .type(MessageType.ERROR).payload("Timeout").build()); }
                        catch (Exception ex) { return AgentMessage.builder()
                                .type(MessageType.ERROR).payload("Error: " + ex.getMessage()).build(); }
                    })
                    .toList();
        }

        // 构建各 Agent 结果
        List<OrchestrationResult.AgentResult> agentResults = new ArrayList<>();
        List<AgentMessage> successResponses = new ArrayList<>();
        for (int i = 0; i < agentIds.size() && i < responses.size(); i++) {
            AgentMessage resp = responses.get(i);
            boolean ok = resp != null && resp.getType() != MessageType.ERROR;
            agentResults.add(new OrchestrationResult.AgentResult(
                    agentIds.get(i), ok,
                    ok ? resp.getPayload() : null,
                    ok ? null : (resp != null ? resp.getPayload() : "null response"),
                    0, Map.of()));
            if (ok) successResponses.add(resp);
        }

        // 聚合成功的结果
        AggregationStrategy strategy = AggregationStrategy.byName(spec.getAggregationStrategy());
        String aggregated = strategy.aggregate(successResponses, spec);

        long elapsed = System.currentTimeMillis() - fanOutStart;
        return OrchestrationResult.success(aggregated, OrchestrationPattern.FAN_OUT,
                agentResults, elapsed);
    }

    // ════════════════════════════════════════════════════════════
    // DEBATE — 多轮辩论
    // ════════════════════════════════════════════════════════════

    /**
     * DEBATE 模式：多个 Agent 就同一议题进行多轮讨论。
     *
     * <p>每轮中，每个 Agent 看到其他 Agent 上一轮的观点，然后补充/反驳。
     * 最后一轮结束后，综合所有观点。</p>
     */
    protected OrchestrationResult executeDebate(OrchestrationSpec spec) {
        List<String> agentIds = resolveAgentIds(spec);
        if (agentIds.size() < 2) {
            return OrchestrationResult.failure(
                    "DEBATE pattern requires at least 2 agents, got " + agentIds.size(),
                    spec.getPattern(), List.of());
        }

        int maxRounds = spec.getMaxDebateRounds();
        String topic = spec.getPayload() != null ? spec.getPayload()
                : (spec.getTask() != null ? spec.getTask() : "");
        log.info("[Debate] Starting {} rounds with agents: {}", maxRounds, agentIds);

        // opinions[agentIndex][round]
        List<List<String>> opinions = new ArrayList<>();
        for (int i = 0; i < agentIds.size(); i++) {
            opinions.add(new ArrayList<>());
        }

        long debateStart = System.currentTimeMillis();

        for (int round = 0; round < maxRounds; round++) {
            log.info("[Debate] Round {}/{}", round + 1, maxRounds);

            for (int i = 0; i < agentIds.size(); i++) {
                String agentId = agentIds.get(i);

                // 构建提示：议题 + 其他 Agent 上轮观点
                StringBuilder prompt = new StringBuilder();
                if (round == 0) {
                    prompt.append("请分析以下问题：\n").append(topic).append("\n\n");
                    prompt.append("请给出你的专业分析和建议。");
                } else {
                    prompt.append("问题：").append(topic).append("\n\n");
                    prompt.append("其他专家的观点如下：\n");
                    for (int j = 0; j < agentIds.size(); j++) {
                        if (j == i) continue;
                        List<String> otherOpinions = opinions.get(j);
                        if (round - 1 < otherOpinions.size()) {
                            prompt.append("— ").append(agentIds.get(j)).append("：")
                                    .append(truncate(otherOpinions.get(round - 1), 200)).append("\n");
                        }
                    }
                    prompt.append("\n基于以上观点，请补充、反驳或综合，给出你的最终分析。");
                }

                // 发送请求
                AgentMessage request = AgentMessage.builder()
                        .type(MessageType.REQUEST)
                        .to(agentId)
                        .payload(prompt.toString())
                        .correlationId("debate-" + agentId + "-r" + round)
                        .ttlMs(spec.getTimeoutMs() / (maxRounds * agentIds.size()))
                        .build();

                AgentMessage response = mesh.send(request).join();
                opinions.get(i).add(response.getPayload() != null
                        ? response.getPayload() : "(no response)");
            }
        }

        // 综合所有 Agent 最后一轮的观点
        List<OrchestrationResult.AgentResult> agentResults = new ArrayList<>();
        StringBuilder synthesis = new StringBuilder();
        synthesis.append("## 辩论结果\n\n");
        synthesis.append("议题：").append(topic).append("\n\n");

        for (int i = 0; i < agentIds.size(); i++) {
            String finalOpinion = opinions.get(i).isEmpty() ? "" : opinions.get(i).get(opinions.get(i).size() - 1);
            synthesis.append("### ").append(agentIds.get(i)).append(" 的最终观点\n\n");
            synthesis.append(finalOpinion).append("\n\n");

            agentResults.add(new OrchestrationResult.AgentResult(
                    agentIds.get(i), true, finalOpinion, null, 0,
                    Map.of("rounds", opinions.get(i).size())));
        }

        long elapsed = System.currentTimeMillis() - debateStart;
        return OrchestrationResult.success(synthesis.toString(),
                OrchestrationPattern.DEBATE, agentResults, elapsed);
    }

    // ════════════════════════════════════════════════════════════
    // DAG — 有向无环图
    // ════════════════════════════════════════════════════════════

    /**
     * DAG 模式：按拓扑序执行有向无环图中的所有节点。
     *
     * <p>DAG 通过 {@link DagDefinition} 定义节点和边的依赖关系。
     * 执行流程：拓扑排序 → 逐层按依赖执行 → 结果传递 → 汇总。</p>
     *
     * <p>使用示例：</p>
     * <pre>{@code
     * DagDefinition dag = DagDefinition.builder()
     *     .node("fetch", "Fetch data", "data-loader")
     *     .node("process", "Process data", "data-processor")
     *     .node("report", "Generate report", "reporter")
     *     .edge("fetch", "process")
     *     .edge("process", "report")
     *     .build();
     *
     * engine.execute(OrchestrationSpec.builder()
     *     .pattern(OrchestrationPattern.DAG)
     *     .config("dag", dag.toConfig())
     *     .timeoutMs(300_000)
     *     .build());
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    protected OrchestrationResult executeDag(OrchestrationSpec spec) {
        long dagStart = System.currentTimeMillis();

        // 1. 解析 DAG 定义
        DagDefinition dag;
        Object dagConfig = spec.getConfig() != null ? spec.getConfig().get("dag") : null;
        if (dagConfig instanceof Map) {
            dag = DagDefinition.fromConfig((Map<String, Object>) dagConfig);
        } else {
            dag = DagDefinition.fromConfig(spec.getConfig());
        }

        if (dag.getNodes().isEmpty()) {
            return OrchestrationResult.failure(
                    "DAG has no nodes. Define nodes via DagDefinition.builder()",
                    spec.getPattern(), List.of());
        }

        // 2. 拓扑排序
        List<String> topoOrder = dag.topologicalSort();
        log.info("[DAG] Topological order: {}", topoOrder);

        if (topoOrder.size() < dag.getNodes().size()) {
            return OrchestrationResult.failure(
                    "DAG contains a cycle (topological sort incomplete: "
                            + topoOrder.size() + "/" + dag.getNodes().size() + ")",
                    spec.getPattern(), List.of());
        }

        // 3. 逐层执行（同层可并行）
        Map<String, String> nodeResults = new LinkedHashMap<>();    // nodeId → result
        Map<String, Boolean> nodeStatus = new LinkedHashMap<>();    // nodeId → done
        Map<String, String> nodeErrors = new LinkedHashMap<>();     // nodeId → error

        // 初始化状态
        for (DagDefinition.DagNode node : dag.getNodes()) {
            nodeStatus.put(node.getId(), false);
        }

        List<OrchestrationResult.AgentResult> agentResults = new ArrayList<>();
        AtomicBoolean anyFailed = new AtomicBoolean(false);

        // 按拓扑序遍历，每轮找可并行执行的节点
        List<String> sortedCopy = new ArrayList<>(topoOrder);

        while (!sortedCopy.isEmpty()) {
            // 找当前所有可执行的节点（所有依赖已完成）
            List<String> readyNodes = new ArrayList<>();
            for (String nodeId : sortedCopy) {
                if (nodeStatus.get(nodeId)) continue;
                List<String> deps = dag.getDependencies(nodeId);
                boolean allDepsMet = deps.stream()
                        .allMatch(d -> nodeStatus.getOrDefault(d, false));
                if (allDepsMet) {
                    readyNodes.add(nodeId);
                }
            }

            if (readyNodes.isEmpty()) {
                // 死锁检测
                log.warn("[DAG] No ready nodes but " + sortedCopy.size() + " remain");
                break;
            }

            // 并行执行就绪节点
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (String nodeId : readyNodes) {
                DagDefinition.DagNode nodeDef = dag.getNodes().stream()
                        .filter(n -> n.getId().equals(nodeId))
                        .findFirst().orElse(null);
                if (nodeDef == null) continue;

                String nodeTask = buildNodeTask(nodeDef, nodeResults, spec);
                String agentId = nodeDef.getAgentId();

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    long nodeStart = System.currentTimeMillis();
                    log.info("[DAG] Executing node: {} → agent: {}", nodeId, agentId);

                    AgentMessage request = AgentMessage.builder()
                            .type(MessageType.REQUEST)
                            .to(agentId)
                            .payload(nodeTask)
                            .correlationId("dag-" + nodeId)
                            .ttlMs(spec.getTimeoutMs() / Math.max(1, dag.getNodes().size()))
                            .build();

                    try {
                        AgentMessage response = mesh.send(request)
                                .get(spec.getTimeoutMs() / Math.max(1, dag.getNodes().size()),
                                        java.util.concurrent.TimeUnit.MILLISECONDS);

                        boolean ok = response.getType() != MessageType.ERROR;
                        synchronized (nodeResults) {
                            if (ok) {
                                nodeResults.put(nodeId, response.getPayload());
                                nodeStatus.put(nodeId, true);
                            } else {
                                nodeErrors.put(nodeId, response.getPayload());
                                nodeStatus.put(nodeId, true);
                                anyFailed.set(true);
                            }
                        }
                        synchronized (agentResults) {
                            agentResults.add(new OrchestrationResult.AgentResult(
                                    agentId, ok, response.getPayload(),
                                    ok ? null : response.getPayload(),
                                    System.currentTimeMillis() - nodeStart,
                                    Map.of("dagNodeId", nodeId)));
                        }
                    } catch (Exception e) {
                        synchronized (nodeErrors) {
                            nodeErrors.put(nodeId, "DAG node failed: " + e.getMessage());
                            nodeStatus.put(nodeId, true);
                            anyFailed.set(true);
                        }
                        synchronized (agentResults) {
                            agentResults.add(new OrchestrationResult.AgentResult(
                                    agentId, false, null,
                                    "DAG node failed: " + e.getMessage(),
                                    System.currentTimeMillis() - nodeStart,
                                    Map.of("dagNodeId", nodeId)));
                        }
                    }
                });

                futures.add(future);
            }

            // 等待这一批完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .join();

            // 从待处理列表中移除
            sortedCopy.removeAll(readyNodes);
        }

        // 4. 构建结果
        StringBuilder output = new StringBuilder();
        output.append("## DAG 执行结果\n\n");

        for (DagDefinition.DagNode node : dag.getNodes()) {
            output.append("### ").append(node.getId())
                    .append(" (").append(node.getAgentId()).append(")\n\n");
            if (nodeErrors.containsKey(node.getId())) {
                output.append("❌ 失败：").append(nodeErrors.get(node.getId())).append("\n\n");
            } else if (nodeResults.containsKey(node.getId())) {
                output.append(nodeResults.get(node.getId())).append("\n\n");
            }
        }

        long elapsed = System.currentTimeMillis() - dagStart;
        return OrchestrationResult.success(
                output.toString(),
                OrchestrationPattern.DAG,
                agentResults,
                elapsed);
    }

    /**
     * 为 DAG 节点构建任务描述，注入前置节点的执行结果。
     */
    private String buildNodeTask(DagDefinition.DagNode node,
                                  Map<String, String> nodeResults,
                                  OrchestrationSpec spec) {
        List<String> deps = List.of(); // we'll resolve from the dag
        // 简单注入：如果节点 task 中包含 {{result.fromNodeId}} 模式
        String task = node.getTask() != null ? node.getTask()
                : (spec.getTask() != null ? spec.getTask() : "");
        for (Map.Entry<String, String> result : nodeResults.entrySet()) {
            String placeholder = "{{" + result.getKey() + "}}";
            if (task.contains(placeholder)) {
                task = task.replace(placeholder,
                        result.getValue() != null ? result.getValue() : "");
            }
        }
        return task;
    }

    // ════════════════════════════════════════════════════════════
    // SUPERVISOR — Supervisor 分解执行
    // ════════════════════════════════════════════════════════════

    /**
     * SUPERVISOR 模式：Supervisor Agent 将任务分解为子任务，
     * 派发给 Worker Agent，然后汇总结果。
     *
     * <p>使用 {@code agentIds.get(0)} 作为 Supervisor，
     * 其余作为 Workers。如果只指定了一个 Agent，所有其他匹配能力的 Agent 作为 Workers。</p>
     */
    protected OrchestrationResult executeSupervisor(OrchestrationSpec spec) {
        List<String> allAgents = resolveAgentIds(spec);
        if (allAgents.isEmpty()) {
            return OrchestrationResult.failure(
                    "No agents for SUPERVISOR pattern", spec.getPattern(), List.of());
        }

        String supervisorId = allAgents.get(0);
        List<String> workers = allAgents.size() > 1
                ? allAgents.subList(1, allAgents.size())
                : findWorkers(supervisorId);

        if (workers.isEmpty()) {
            return OrchestrationResult.failure(
                    "No worker agents available for SUPERVISOR", spec.getPattern(), List.of());
        }

        log.info("[Supervisor] supervisor={} workers={}", supervisorId, workers);

        // Step 1: Supervisor 分解任务
        String task = spec.getPayload() != null ? spec.getPayload()
                : (spec.getTask() != null ? spec.getTask() : "");
        String decomposePrompt = "你是一个任务分解专家。请将以下任务分解为" + workers.size()
                + "个子任务，分别分配给以下 Agent 执行：\n"
                + String.join(", ", workers) + "\n\n"
                + "任务：" + task + "\n\n"
                + "请为每个 Agent 给出明确的子任务描述。\n"
                + "格式：agentId: 子任务描述";

        AgentMessage decomposeRequest = AgentMessage.builder()
                .type(MessageType.REQUEST)
                .to(supervisorId)
                .payload(decomposePrompt)
                .correlationId("supervisor-decompose")
                .ttlMs(spec.getTimeoutMs() / 2)
                .build();

        AgentMessage decomposition = mesh.send(decomposeRequest).join();
        String plan = decomposition.getPayload() != null ? decomposition.getPayload() : "";

        // Step 2: 并行发派给 Workers
        List<CompletableFuture<AgentMessage>> workerFutures = workers.stream()
                .map(workerId -> {
                    String subTask = extractSubTask(plan, workerId, task);
                    AgentMessage workerRequest = AgentMessage.builder()
                            .type(MessageType.REQUEST)
                            .to(workerId)
                            .payload(subTask)
                            .correlationId("supervisor-worker-" + workerId)
                            .ttlMs(spec.getTimeoutMs() / 2)
                            .build();
                    return mesh.send(workerRequest);
                })
                .toList();

        // Step 3: 收集结果
        List<OrchestrationResult.AgentResult> agentResults = new ArrayList<>();
        StringBuilder aggregatedOutput = new StringBuilder();
        aggregatedOutput.append("## Supervisor 执行结果\n\n");
        aggregatedOutput.append("任务：").append(task).append("\n\n");
        aggregatedOutput.append("### 分解计划\n").append(plan).append("\n\n");

        for (int i = 0; i < workers.size(); i++) {
            try {
                AgentMessage response = workerFutures.get(i)
                        .get(spec.getTimeoutMs() / 2, TimeUnit.MILLISECONDS);
                boolean ok = response.getType() != MessageType.ERROR;
                agentResults.add(new OrchestrationResult.AgentResult(
                        workers.get(i), ok, response.getPayload(),
                        ok ? null : response.getPayload(), 0, Map.of()));

                aggregatedOutput.append("### ").append(workers.get(i)).append(" 的执行结果\n\n");
                aggregatedOutput.append(response.getPayload()).append("\n\n");
            } catch (Exception e) {
                agentResults.add(new OrchestrationResult.AgentResult(
                        workers.get(i), false, null, e.getMessage(), 0, Map.of()));
                aggregatedOutput.append("### ").append(workers.get(i)).append(" 执行失败\n\n");
                aggregatedOutput.append(e.getMessage()).append("\n\n");
            }
        }

        return OrchestrationResult.success(aggregatedOutput.toString(),
                OrchestrationPattern.SUPERVISOR, agentResults, 0);
    }

    // ════════════════════════════════════════════════════════════
    // 内部方法
    // ════════════════════════════════════════════════════════════

    /** 解析目标 Agent ID */
    private String resolveAgentId(OrchestrationSpec spec) {
        if (spec.getAgentIds() != null && !spec.getAgentIds().isEmpty()) {
            return spec.getAgentIds().get(0);
        }
        if (spec.getRequiredCapabilities() != null && !spec.getRequiredCapabilities().isEmpty()) {
            for (String cap : spec.getRequiredCapabilities()) {
                List<AgentRef> refs = mesh.findByCapability(cap);
                if (refs != null && !refs.isEmpty()) {
                    return refs.get(0).getAgentId();
                }
            }
        }
        // 默认选第一个 Agent
        List<AgentRef> all = mesh.getAllAgents();
        return all.isEmpty() ? null : all.get(0).getAgentId();
    }

    /** 解析目标 Agent ID 列表 */
    private List<String> resolveAgentIds(OrchestrationSpec spec) {
        if (spec.getAgentIds() != null && !spec.getAgentIds().isEmpty()) {
            return spec.getAgentIds();
        }
        if (spec.getRequiredCapabilities() != null && !spec.getRequiredCapabilities().isEmpty()) {
            return spec.getRequiredCapabilities().stream()
                    .flatMap(cap -> mesh.findByCapability(cap).stream())
                    .map(AgentRef::getAgentId)
                    .distinct()
                    .collect(Collectors.toList());
        }
        return mesh.getAllAgents().stream()
                .map(AgentRef::getAgentId)
                .collect(Collectors.toList());
    }

    /** 构建 Agent 请求消息 */
    private AgentMessage buildRequest(String targetId, OrchestrationSpec spec) {
        String payload = spec.getPayload() != null ? spec.getPayload()
                : (spec.getTask() != null ? spec.getTask() : "");
        return AgentMessage.builder()
                .type(MessageType.REQUEST)
                .to(targetId)
                .from("orchestrator")
                .correlationId("orch-" + UUID.randomUUID().toString().substring(0, 8))
                .traceId("trace-" + System.currentTimeMillis())
                .payload(payload)
                .ttlMs(spec.getTimeoutMs())
                .build();
    }

    /** 从分解计划中提取指定 Worker 的子任务 */
    private String extractSubTask(String plan, String workerId, String defaultTask) {
        if (plan == null || plan.isEmpty()) return defaultTask;
        // 尝试解析 "agentId: 子任务" 格式
        for (String line : plan.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(workerId + ":") || trimmed.startsWith(workerId + "：")) {
                return trimmed.substring(trimmed.indexOf(':') + 1).trim();
            }
            if (trimmed.startsWith("- " + workerId + ":") || trimmed.startsWith("- " + workerId + "：")) {
                return trimmed.substring(trimmed.indexOf(':') + 1).trim();
            }
        }
        return defaultTask;
    }

    /** 查找除指定 Agent 外的可用 Worker */
    private List<String> findWorkers(String excludeAgentId) {
        return mesh.getAllAgents().stream()
                .map(AgentRef::getAgentId)
                .filter(id -> !id.equals(excludeAgentId))
                .collect(Collectors.toList());
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
