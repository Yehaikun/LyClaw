package lyjew.com.lyclaw.plan.controller;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.interceptor.InterceptorChain;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.plan.impl.TaskGraphImpl;
import lyjew.com.lyclaw.task.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 任务规划 REST API 控制器，提供计划生成、修订、分解和图构建接口。
 *
 * <p><b>设计动机</b>：LyClaw 作为多智能体协作框架，需要一个统一的计划管理层来
 * 协调不同智能体的任务执行。PlanController 是这一层的对外 REST 接口，
 * 它使得前端、CLI、或其他微服务可以通过标准 HTTP 协议提交规划请求、
 * 查询执行进度、以及动态调整计划策略。通过将规划逻辑封装为 REST 端点，
 * 实现了规划决策与智能体执行之间的解耦，计划可以在不同执行环境中复用。</p>
 *
 * <p><b>工作流程</b>：典型的计划生命周期如下：
 * <ol>
 *   <li><b>计划生成 (POST /api/plan/plan)</b> — 客户端提交用户意图和策略选择，
 *       控制器根据策略名称（dag/cot/react/hierarchical）选择合适的规划器，
 *       生成包含任务节点及其依赖关系的计划图。</li>
 *   <li><b>计划校验 (POST /api/plan/validate)</b> — 对生成的任务计划进行结构校验，
 *       检查节点依赖是否存在循环引用、必填字段是否完整、超时时间是否合理等。</li>
 *   <li><b>计划分解 (POST /api/plan/decompose)</b> — 当任务描述较为宏观时，
 *       使用指定的分解策略（按阶段、按领域、并行独立、LLM驱动等）将根任务
 *       进一步拆分为更细粒度的子任务图，便于多个 Agent 并行执行。</li>
 *   <li><b>图构建 (POST /api/plan/graph)</b> — 允许直接通过 JSON 格式定义
 *       节点和边的关系来构建完整的任务图，适用于前端可视化拖拽生成的计划。</li>
 *   <li><b>进度跟踪 (GET /api/plan/progress/{planId})</b> — 查询某个计划
 *       的实时执行进度，包括当前正在执行的节点、完成百分比、预计剩余时间等。</li>
 *   <li><b>计划修订 (POST /api/plan/revise)</b> — 当反思引擎检测到计划存在问题
 *       （如节点顺序错误、缺失步骤）时，根据反馈修订原计划。</li>
 * </ol>
 * </p>
 *
 * <p><b>策略切换机制</b>：控制器通过 Spring 的 {@code @Qualifier} 注解注入
 * 多个规划器实现（DAGTaskPlanner / cotPlanner / reActPlanner / hierarchicalPlanner），
 * {@link #selectPlanner(String)} 方法根据请求中的策略名称进行运行时选择。
 * 如果指定策略对应的规划器 Bean 不可用（为 null），则自动回退到默认的 DAGTaskPlanner，
 * 保证了系统的容错性和可用性。</p>
 *
 * <p><b>端点列表</b>：所有端点均返回标准 JSON 格式的 ResponseEntity：
 * <ul>
 *   <li><b>POST /api/plan/plan</b> — 根据用户意图生成任务计划</li>
 *   <li><b>POST /api/plan/revise</b> — 根据反馈修订现有计划</li>
 *   <li><b>POST /api/plan/decompose</b> — 使用指定策略分解任务</li>
 *   <li><b>GET /api/plan/progress/{planId}</b> — 查询计划执行进度</li>
 *   <li><b>POST /api/plan/validate</b> — 校验计划的有效性</li>
 *   <li><b>POST /api/plan/graph</b> — 从 JSON 构建任务图</li>
 *   <li><b>GET /api/plan/strategies</b> — 列出可用的分解策略及其描述</li>
 * </ul>
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/plan")
public class PlanController {

    /** 默认规划器 (DAGTaskPlanner) */
    private final TaskPlanner defaultPlanner;
    /** 链式思考规划器（可选） */
    private final TaskPlanner cotPlanner;
    /** ReAct 规划器（可选） */
    private final TaskPlanner reActPlanner;
    /** 层次化规划器（可选） */
    private final TaskPlanner hierarchicalPlanner;
    private final PlanValidator planValidator;
    private final InterceptorChain interceptorChain;
    private final ChatFacade chatFacade;

    /**
     * 构造函数，注入多种规划策略和依赖组件。
     *
     * @param defaultPlanner       默认 DAG 规划器
     * @param cotPlanner           CoT 规划器（可为 null）
     * @param reActPlanner         ReAct 规划器（可为 null）
     * @param hierarchicalPlanner  层次化规划器（可为 null）
     * @param planValidator        计划校验器
     * @param interceptorChain     拦截器链
     * @param chatFacade           聊天门面（可为 null）
     */
    public PlanController(@org.springframework.beans.factory.annotation.Qualifier("DAGTaskPlanner")
                          TaskPlanner defaultPlanner,
                          @org.springframework.beans.factory.annotation.Qualifier("cotPlanner")
                          @org.springframework.lang.Nullable TaskPlanner cotPlanner,
                          @org.springframework.beans.factory.annotation.Qualifier("reActPlanner")
                          @org.springframework.lang.Nullable TaskPlanner reActPlanner,
                          @org.springframework.beans.factory.annotation.Qualifier("hierarchicalPlanner")
                          @org.springframework.lang.Nullable TaskPlanner hierarchicalPlanner,
                          PlanValidator planValidator,
                          InterceptorChain interceptorChain,
                          @org.springframework.lang.Nullable ChatFacade chatFacade) {
        this.defaultPlanner = defaultPlanner;
        this.cotPlanner = cotPlanner;
        this.reActPlanner = reActPlanner;
        this.hierarchicalPlanner = hierarchicalPlanner;
        this.planValidator = planValidator;
        this.interceptorChain = interceptorChain;
        this.chatFacade = chatFacade;
    }

    /**
     * 根据用户意图生成任务计划。
     *
     * @param request 包含 strategy、userIntent、sessionId 的计划请求
     * @return 包含计划、策略、节点数、预估时间和校验结果的响应
     */
    @PostMapping("/plan")
    public ResponseEntity<Map<String, Object>> plan(@RequestBody PlanRequest request) {
        log.info("\n\n══════════════════════════════════");
        log.info("  [规划] 收到任务规划请求");
        log.info("──────────────────────────────────");
        log.info("  策略    : {}", request.getStrategy() != null ? request.getStrategy() : "dag");
        log.info("  意图    : {}", request.getUserIntent() != null ? request.getUserIntent() : "(从上下文提取)");
        log.info("  会话    : {}", request.getSessionId());
        log.info("══════════════════════════════════");
        ChatContext context = buildContext(request);
        TaskPlanner planner = selectPlanner(request.getStrategy());
        log.debug("使用规划器: {}", planner.getClass().getSimpleName());
        TaskPlan plan = planner.plan(context, request.getUserIntent());
        PlanValidator.ValidationResult validation = planValidator.validate(plan);

        Map<String, Object> response = new HashMap<>();
        response.put("plan", plan);
        response.put("nodes", plan.getNodes() != null ? plan.getNodes() : List.of());
        response.put("strategy", request.getStrategy() != null ? request.getStrategy() : "dag");
        response.put("nodeCount", plan.getNodes() != null ? plan.getNodes().size() : 0);
        response.put("estimatedTime", plan.getEstimatedCompletionTime());
        response.put("valid", validation.isValid());
        response.put("validationErrors", validation.getErrors());

        log.info("\n──────────────────────────────────");
        log.info("  [规划] 规划完成");
        log.info("──────────────────────────────────");
        log.info("  规划器  : {}", planner.getClass().getSimpleName());
        log.info("  节点数  : {}", plan.getNodes() != null ? plan.getNodes().size() : 0);
        log.info("  校验结果: {}", validation.isValid() ? "通过" : "失败 (" + validation.getErrors().size() + " 个错误)");
        log.info("──────────────────────────────────");
        if (plan.getNodes() != null) {
            int i = 1;
            for (TaskNode node : plan.getNodes()) {
                log.info("  [{}/{}] {} | deps={} | tools={} | timeout={}ms",
                        i++, plan.getNodes().size(),
                        String.format("%-12s %s", node.getType(), node.getDescription().length() > 60
                                ? node.getDescription().substring(0, 60) + "..." : node.getDescription()),
                        node.getDependencies() != null ? node.getDependencies() : "[]",
                        node.getRequiredTools() != null ? node.getRequiredTools() : "[]",
                        node.getTimeoutMs());
            }
        }
        log.info("══════════════════════════════════\n");
        return ResponseEntity.ok(response);
    }

    /**
     * 根据反思反馈修订现有计划。
     *
     * @param request 包含 currentPlan、feedback、reason 的修订请求
     * @return 修订后的任务计划
     */
    @PostMapping("/revise")
    public ResponseEntity<TaskPlan> revise(@RequestBody ReviseRequest request) {
        ReflectionFeedback feedback = ReflectionFeedback.builder()
                .suggestedStrategy(request.getFeedback())
                .adjustedPrompt(request.getReason())
                .build();
        TaskPlan revised = defaultPlanner.revise(request.getCurrentPlan(), feedback);
        return ResponseEntity.ok(revised);
    }

    /**
     * 使用指定策略将根任务分解为子任务图。
     *
     * @param request 包含 taskDescription、strategy、planner 的请求映射
     * @return 包含完整任务图、节点数、关键路径和最大并行度的响应
     */
    @PostMapping("/decompose")
    public ResponseEntity<Map<String, Object>> decompose(@RequestBody Map<String, Object> request) {
        String description = (String) request.getOrDefault("taskDescription", "default task");
        String strategyName = (String) request.getOrDefault("strategy", "BY_PHASE");

        DecompositionStrategy strategy;
        try {
            strategy = DecompositionStrategy.valueOf(strategyName.toUpperCase());
        } catch (IllegalArgumentException e) {
            strategy = DecompositionStrategy.BY_PHASE;
        }

        TaskNode rootTask = new TaskNode("root-decomp", "ROOT", description, List.of(), List.of(), 30_000L);
        TaskPlanner planner = selectPlanner((String) request.get("planner"));
        PlanGraph graph = planner.decompose(rootTask, strategy);
        TaskGraphImpl fullGraph = convertToFullGraph(graph);

        Map<String, Object> response = new HashMap<>();
        response.put("graph", fullGraph);
        response.put("nodeCount", fullGraph.getNodeMap().size());
        response.put("criticalPath", fullGraph.getCriticalPath().stream().map(TaskNode::getNodeId).toList());
        response.put("maxParallelism", fullGraph.getMaxParallelism());
        response.put("progress", fullGraph.getProgressDetail());
        return ResponseEntity.ok(response);
    }

    /** 查询计划执行进度（当前为模拟实现）。 */
    @GetMapping("/progress/{planId}")
    public ResponseEntity<Map<String, Object>> progress(@PathVariable String planId) {
        Map<String, Object> progress = new HashMap<>();
        progress.put("planId", planId);
        progress.put("status", "RUNNING");
        progress.put("progress", 0.5);
        progress.put("currentNode", "task-in-progress");
        return ResponseEntity.ok(progress);
    }

    /** 校验任务计划的有效性。 */
    @PostMapping("/validate")
    public ResponseEntity<PlanValidator.ValidationResult> validate(@RequestBody TaskPlan plan) {
        return ResponseEntity.ok(planValidator.validate(plan));
    }

    /**
     * 从 JSON 定义构建任务图。
     *
     * @param request 包含 nodes 和 edges 列表的请求映射
     * @return 构建好的 TaskGraphImpl 实例
     */
    @PostMapping("/graph")
    public ResponseEntity<TaskGraphImpl> buildGraph(@RequestBody Map<String, Object> request) {
        TaskGraphImpl graph = new TaskGraphImpl();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) request.getOrDefault("nodes", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, String>> edges = (List<Map<String, String>>) request.getOrDefault("edges", List.of());

        for (Map<String, Object> nodeDef : nodes) {
            String nodeId = (String) nodeDef.get("nodeId");
            String type = (String) nodeDef.getOrDefault("type", "EXECUTE");
            String description = (String) nodeDef.getOrDefault("description", "");
            @SuppressWarnings("unchecked")
            List<String> tools = (List<String>) nodeDef.getOrDefault("tools", List.of());
            long timeout = nodeDef.containsKey("timeoutMs")
                    ? ((Number) nodeDef.get("timeoutMs")).longValue() : 30_000L;
            graph.addNode(new TaskNode(nodeId, type, description, tools, List.of(), timeout));
        }

        for (Map<String, String> edgeDef : edges) {
            String from = edgeDef.get("from");
            String to = edgeDef.get("to");
            if (from != null && to != null) graph.addEdge(from, to);
        }

        return ResponseEntity.ok(graph);
    }

    /** 列出所有可用的分解策略及其描述。 */
    @GetMapping("/strategies")
    public ResponseEntity<List<Map<String, String>>> listStrategies() {
        List<Map<String, String>> strategies = new ArrayList<>();
        for (DecompositionStrategy strategy : DecompositionStrategy.values()) {
            Map<String, String> info = new HashMap<>();
            info.put("name", strategy.name());
            info.put("description", describeStrategy(strategy));
            strategies.add(info);
        }
        return ResponseEntity.ok(strategies);
    }

    /**
     * 根据策略名称选择对应的规划器。
     * 如果指定规划器不可用（为 null），回退到默认规划器。
     */
    private TaskPlanner selectPlanner(String strategyName) {
        if (strategyName == null || strategyName.isBlank()) return defaultPlanner;
        return switch (strategyName.toLowerCase()) {
            case "cot" -> cotPlanner != null ? cotPlanner : defaultPlanner;
            case "react" -> reActPlanner != null ? reActPlanner : defaultPlanner;
            case "hierarchical" -> hierarchicalPlanner != null ? hierarchicalPlanner : defaultPlanner;
            default -> defaultPlanner;
        };
    }

    /** 将 PlanGraph 转换为 TaskGraphImpl（完整图结构）。 */
    private TaskGraphImpl convertToFullGraph(PlanGraph graph) {
        TaskGraphImpl full = new TaskGraphImpl();
        for (TaskNode node : graph.getNodeMap().values()) full.addNode(node);
        for (TaskNode node : graph.getNodeMap().values()) {
            List<String> deps = node.getDependencies();
            if (deps != null) {
                for (String dep : deps) full.addEdge(dep, node.getNodeId());
            }
        }
        return full;
    }

    /** 返回分解策略的英文描述文本。 */
    private String describeStrategy(DecompositionStrategy strategy) {
        return switch (strategy) {
            case SEQUENTIAL -> "Sequential decomposition: A -> B -> C linear chain";
            case BY_DOMAIN -> "Domain decomposition: group and process by knowledge domain";
            case BY_PHASE -> "Phase decomposition: analyze -> design -> implement -> verify";
            case PARALLEL_INDEPENDENT -> "Independent parallel: identify independent sub-tasks";
            case LLM_DRIVEN -> "LLM-driven: let LLM decide how to decompose";
            case TREE -> "Tree recursive decomposition: top-down hierarchical splitting";
        };
    }

    /** 从 PlanRequest 构建 ChatContext 上下文对象。 */
    private ChatContext buildContext(PlanRequest request) {
        Session session = Session.builder().sessionId(request.getSessionId()).build();
        // TODO: 记忆系统重新设计后恢复 MemoryContent 注入
        ChatRequest chatRequest = ChatRequest.builder()
                .sessionId(request.getSessionId()).messages(new ArrayList<>()).build();
        return new ChatContext(chatRequest, session, new ArrayList<>(), interceptorChain, chatFacade);
    }
}
