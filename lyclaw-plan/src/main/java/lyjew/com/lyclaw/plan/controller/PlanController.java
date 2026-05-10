package lyjew.com.lyclaw.plan.controller;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.interceptor.InterceptorChain;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.plan.impl.TaskGraphImpl;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.task.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/plan")
public class PlanController {

    private final TaskPlanner defaultPlanner;
    private final TaskPlanner cotPlanner;
    private final TaskPlanner reActPlanner;
    private final TaskPlanner hierarchicalPlanner;
    private final PlanValidator planValidator;
    private final InterceptorChain interceptorChain;
    private final ModelProvider modelProvider;

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
                          @org.springframework.lang.Nullable ModelProvider modelProvider) {
        this.defaultPlanner = defaultPlanner;
        this.cotPlanner = cotPlanner;
        this.reActPlanner = reActPlanner;
        this.hierarchicalPlanner = hierarchicalPlanner;
        this.planValidator = planValidator;
        this.interceptorChain = interceptorChain;
        this.modelProvider = modelProvider;
    }

    @PostMapping("/plan")
    public ResponseEntity<Map<String, Object>> plan(@RequestBody PlanRequest request) {
        ChatContext context = buildContext(request);
        TaskPlanner planner = selectPlanner(request.getStrategy());
        TaskPlan plan = planner.plan(context, request.getUserIntent());
        PlanValidator.ValidationResult validation = planValidator.validate(plan);

        Map<String, Object> response = new HashMap<>();
        response.put("plan", plan);
        response.put("strategy", request.getStrategy() != null ? request.getStrategy() : "dag");
        response.put("nodeCount", plan.getNodes() != null ? plan.getNodes().size() : 0);
        response.put("estimatedTime", plan.getEstimatedCompletionTime());
        response.put("valid", validation.isValid());
        response.put("validationErrors", validation.getErrors());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/revise")
    public ResponseEntity<TaskPlan> revise(@RequestBody ReviseRequest request) {
        ReflectionFeedback feedback = ReflectionFeedback.builder()
                .suggestedStrategy(request.getFeedback())
                .adjustedPrompt(request.getReason())
                .build();
        TaskPlan revised = defaultPlanner.revise(request.getCurrentPlan(), feedback);
        return ResponseEntity.ok(revised);
    }

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

    @GetMapping("/progress/{planId}")
    public ResponseEntity<Map<String, Object>> progress(@PathVariable String planId) {
        Map<String, Object> progress = new HashMap<>();
        progress.put("planId", planId);
        progress.put("status", "RUNNING");
        progress.put("progress", 0.5);
        progress.put("currentNode", "task-in-progress");
        return ResponseEntity.ok(progress);
    }

    @PostMapping("/validate")
    public ResponseEntity<PlanValidator.ValidationResult> validate(@RequestBody TaskPlan plan) {
        return ResponseEntity.ok(planValidator.validate(plan));
    }

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

    private TaskPlanner selectPlanner(String strategyName) {
        if (strategyName == null || strategyName.isBlank()) return defaultPlanner;
        return switch (strategyName.toLowerCase()) {
            case "cot" -> cotPlanner != null ? cotPlanner : defaultPlanner;
            case "react" -> reActPlanner != null ? reActPlanner : defaultPlanner;
            case "hierarchical" -> hierarchicalPlanner != null ? hierarchicalPlanner : defaultPlanner;
            default -> defaultPlanner;
        };
    }

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

    private ChatContext buildContext(PlanRequest request) {
        Session session = Session.builder().sessionId(request.getSessionId()).build();
        MemoryContent memory = new MemoryContent("", "", false, List.of(), 0.0);
        ChatRequest chatRequest = ChatRequest.builder()
                .sessionId(request.getSessionId()).messages(new ArrayList<>()).build();
        return new ChatContext(chatRequest, session, memory, new ArrayList<>(), interceptorChain, modelProvider);
    }
}
