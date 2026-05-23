package lyjew.com.lyclaw.plan.impl;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.chat.ChatModel;
import lyjew.com.lyclaw.config.PlanProperties;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;
import lyjew.com.lyclaw.task.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import reactor.core.scheduler.Schedulers;
import reactor.core.publisher.Mono;

/**
 * 混合规划器——规则优先 + LLM 回退，结合记忆上下文的任务规划引擎。
 *
 * <p>规划策略：
 * <ol>
 *   <li>规则评估：用正则匹配复杂度关键词，得出置信度</li>
 *   <li>高置信度（≥0.5）：直接用规则生成 DAG 计划</li>
 *   <li>低置信度（&lt;0.5）：回退到 LLM 驱动的任务分解</li>
 *   <li>记忆注入：从 ChatContext 提取记忆上下文，丰富规划 prompt</li>
 * </ol>
 */
@Service
public class HybridPlanner implements TaskPlanner {

    private static final Logger log = LoggerFactory.getLogger(HybridPlanner.class);

    private final TaskDecomposer taskDecomposer;
    private final ChatFacade chatFacade;

    public HybridPlanner(TaskDecomposer taskDecomposer,
                         @org.springframework.lang.Nullable ChatFacade chatFacade) {
        this.taskDecomposer = taskDecomposer;
        this.chatFacade = chatFacade;
    }

    private static final Pattern COMPLEX_PATTERN = Pattern.compile(
            "(?i)\\b(build|create|develop|implement|design|deploy|migrate|refactor|"
                    + "optimize|integrate|configure|orchestrate|analyze|investigate|"
                    + "transform|generate|compare|evaluate|summarize|translate|review|"
                    + "同时|并行|并且|以及|另外|此外|首先.*然后.*最后|"
                    + "第一步|第二步|第三步|阶段|步骤)\\b");

    private static final Pattern SIMPLE_PATTERN = Pattern.compile(
            "(?i)\\b(what is|who is|when|where|how to|define|explain|describe|list|show|get|fetch)\\b");

    private long defaultTimeoutMs = 30_000L;
    private double confidenceThreshold = 0.5;

    @Autowired
    public void setPlanProperties(PlanProperties props) {
        this.defaultTimeoutMs = props.getDefaultTimeoutMs();
        this.confidenceThreshold = props.getHybridConfidenceThreshold();
    }

    @Override
    public TaskPlan plan(ChatContext context, String userIntent) {
        String intent = extractIntent(context, userIntent);
        double confidence = assessConfidence(intent);

        log.info("HybridPlanner: intent={}, confidence={}",
                intent.length() > 80 ? intent.substring(0, 80) + "..." : intent, confidence);

        if (confidence >= confidenceThreshold) {
            return buildRulesBasedPlan(intent);
        }

        if (chatFacade != null) {
            return buildLlmBasedPlan(context, intent);
        }

        log.warn("ChatFacade 不可用，回退到规则规划");
        return buildRulesBasedPlan(intent);
    }

    @Override
    public TaskPlan plan(ChatContext context) {
        return plan(context, null);
    }

    @Override
    public TaskPlan revise(TaskPlan original, ReflectionFeedback feedback) {
        if (original == null || feedback == null) return original;
        String strategy = feedback.getSuggestedStrategy() != null
                ? feedback.getSuggestedStrategy().toLowerCase() : "";

        if (feedback.getQualityScore() < 0.3 || strategy.contains("replan")) {
            String adjusted = feedback.getAdjustedPrompt();
            if (adjusted != null && !adjusted.isBlank()) {
                return buildRulesBasedPlan(adjusted);
            }
        }
        return original;
    }

    @Override
    public TaskPlan optimize(AgentResult previousResult) {
        if (previousResult == null || previousResult.getSummary() == null) return null;
        String nodeId = "opt-" + UUID.randomUUID().toString().substring(0, 8);
        return new SimpleTaskPlan(List.of(new TaskNode(nodeId, "OPTIMIZE",
                "Optimize: " + previousResult.getSummary(),
                List.of(), List.of(), defaultTimeoutMs)));
    }

    @Override
    public PlanGraph decompose(TaskNode rootTask, DecompositionStrategy strategy) {
        if (rootTask == null || strategy == null) {
            PlanGraph graph = new PlanGraph();
            if (rootTask != null) graph.addNode(rootTask);
            return graph;
        }
        PlanGraph graph = new PlanGraph();
        graph.addNode(rootTask);

        String prefix = rootTask.getNodeId() + "-dec";
        List<TaskNode> subtasks = taskDecomposer.decompose(rootTask.getDescription(), strategy);
        for (TaskNode sub : subtasks) {
            graph.addNode(sub);
            if (sub.getDependencies() != null) {
                for (String dep : sub.getDependencies()) {
                    graph.addEdge(dep, sub.getNodeId());
                }
            }
        }
        if (subtasks.isEmpty()) {
            TaskNode fallback = new TaskNode(prefix + "-0", "EXECUTE",
                    rootTask.getDescription(), rootTask.getRequiredTools(),
                    List.of(rootTask.getNodeId()), rootTask.getTimeoutMs());
            graph.addNode(fallback);
            graph.addEdge(rootTask.getNodeId(), fallback.getNodeId());
        }
        return graph;
    }

    // ── 规则评估 ──

    private double assessConfidence(String intent) {
        if (intent == null || intent.isBlank()) return 0.8; // 空意图默认高置信简单任务

        long complexHits = COMPLEX_PATTERN.matcher(intent).results().count();
        boolean isSimple = SIMPLE_PATTERN.matcher(intent).find();

        if (isSimple && complexHits == 0) return 0.9;
        if (complexHits >= 4) return 0.8;
        if (complexHits >= 2) return 0.6;
        if (complexHits == 1) return 0.4;
        return 0.2; // 无匹配关键词，置信度低
    }

    // ── 规则计划 ──

    private TaskPlan buildRulesBasedPlan(String intent) {
        long complexHits = COMPLEX_PATTERN.matcher(intent).results().count();
        boolean isSimple = SIMPLE_PATTERN.matcher(intent).find();

        if (isSimple && complexHits <= 1) return buildSimplePlan(intent);
        if (complexHits <= 3) return buildMediumPlan(intent);
        return buildComplexPlan(intent);
    }

    private TaskPlan buildSimplePlan(String intent) {
        String id = "task-" + UUID.randomUUID().toString().substring(0, 8);
        return new SimpleTaskPlan(List.of(
                new TaskNode(id, "EXECUTE", intent, List.of(), List.of(), 10_000L)));
    }

    private TaskPlan buildMediumPlan(String intent) {
        String prefix = "mid-" + UUID.randomUUID().toString().substring(0, 8);
        List<TaskNode> nodes = new ArrayList<>();
        TaskNode analyze = new TaskNode(prefix + "-ana", "ANALYZE",
                "Analyze: " + intent, List.of("knowledge_search"), List.of(), defaultTimeoutMs);
        nodes.add(analyze);
        TaskNode plan = new TaskNode(prefix + "-pln", "PLAN",
                "Plan approach for: " + intent, List.of(), List.of(analyze.getNodeId()), defaultTimeoutMs);
        nodes.add(plan);
        TaskNode execute = new TaskNode(prefix + "-exe", "EXECUTE",
                "Execute: " + intent, List.of(), List.of(plan.getNodeId()), defaultTimeoutMs);
        nodes.add(execute);
        TaskNode verify = new TaskNode(prefix + "-vfy", "VERIFY",
                "Verify result of: " + intent, List.of(), List.of(execute.getNodeId()), defaultTimeoutMs);
        nodes.add(verify);
        return new SimpleTaskPlan(nodes);
    }

    private TaskPlan buildComplexPlan(String intent) {
        String prefix = "cx-" + UUID.randomUUID().toString().substring(0, 8);
        List<TaskNode> nodes = new ArrayList<>();
        TaskNode root = new TaskNode(prefix + "-root", "ANALYZE",
                "Analyze complex task: " + intent, List.of("knowledge_search"), List.of(), defaultTimeoutMs);
        nodes.add(root);
        TaskNode branchA = new TaskNode(prefix + "-a", "RESEARCH",
                "Research: " + intent, List.of("web_search"), List.of(root.getNodeId()), defaultTimeoutMs);
        nodes.add(branchA);
        TaskNode branchB = new TaskNode(prefix + "-b", "DESIGN",
                "Design: " + intent, List.of(), List.of(root.getNodeId()), defaultTimeoutMs);
        nodes.add(branchB);
        TaskNode branchC = new TaskNode(prefix + "-c", "PREPARE",
                "Prepare: " + intent, List.of("file_read"), List.of(root.getNodeId()), defaultTimeoutMs);
        nodes.add(branchC);
        TaskNode merge = new TaskNode(prefix + "-merge", "INTEGRATE",
                "Integrate: " + intent, List.of(),
                List.of(branchA.getNodeId(), branchB.getNodeId(), branchC.getNodeId()), defaultTimeoutMs);
        nodes.add(merge);
        TaskNode execute = new TaskNode(prefix + "-exe", "EXECUTE",
                "Execute: " + intent, List.of(), List.of(merge.getNodeId()), defaultTimeoutMs * 2);
        nodes.add(execute);
        TaskNode verify = new TaskNode(prefix + "-vfy", "VERIFY",
                "Verify: " + intent, List.of(), List.of(execute.getNodeId()), defaultTimeoutMs);
        nodes.add(verify);
        return new SimpleTaskPlan(nodes);
    }

    // ── LLM 回退计划 ──

    @SuppressWarnings("unchecked")
    private TaskPlan buildLlmBasedPlan(ChatContext context, String intent) {
        try {
            List<Message> messages = context != null && context.getMessages() != null
                    ? new ArrayList<>(context.getMessages()) : new ArrayList<>();

            StringBuilder prompt = new StringBuilder();
            prompt.append("You are a task planner. Decompose the following user intent into a DAG of subtasks.\n\n");
            prompt.append("User intent: ").append(intent).append("\n\n");

            // TODO: 记忆系统重新设计后恢复记忆上下文注入

            prompt.append("Output a JSON array of task nodes. Each node: {\"type\":\"ANALYZE|PLAN|EXECUTE|VERIFY\","
                    + "\"description\":\"...\",\"dependencies\":[\"nodeId\"]}.\n");
            prompt.append("The first node must have type ANALYZE. Provide 2-5 nodes. Output ONLY the JSON array.");

            messages.add(Message.user(prompt.toString()));
            ChatRequest req = ChatRequest.builder()
                    .messages(messages)
                    .temperature(0.1)
                    .maxTokens(1024)
                    .build();

            ModelResponse response = Mono.fromCallable(() -> chatFacade.chat(req))
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(Duration.ofSeconds(30));
            String json = response != null ? response.getContent() : null;
            if (json != null) {
                return parseLlmJson(json, intent);
            }
        } catch (Exception e) {
            log.warn("LLM 规划失败，回退到规则规划: {}", e.getMessage());
        }
        return buildRulesBasedPlan(intent);
    }

    private TaskPlan parseLlmJson(String json, String intent) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode arr = mapper.readTree(extractJsonArray(json));
            List<TaskNode> nodes = new ArrayList<>();
            String prefix = "llm-" + UUID.randomUUID().toString().substring(0, 8);

            for (int i = 0; i < arr.size(); i++) {
                com.fasterxml.jackson.databind.JsonNode n = arr.get(i);
                String type = n.has("type") ? n.get("type").asText() : "EXECUTE";
                String desc = n.has("description") ? n.get("description").asText() : intent;
                List<String> deps = new ArrayList<>();
                if (n.has("dependencies") && n.get("dependencies").isArray()) {
                    n.get("dependencies").forEach(d -> deps.add(prefix + "-" + d.asText()));
                }
                nodes.add(new TaskNode(prefix + "-" + i, type.toUpperCase(), desc,
                        List.of(), deps, defaultTimeoutMs));
            }

            if (nodes.isEmpty()) {
                return buildRulesBasedPlan(intent);
            }
            return new SimpleTaskPlan(nodes);
        } catch (Exception e) {
            log.warn("LLM 输出解析失败: {}", e.getMessage());
            return buildRulesBasedPlan(intent);
        }
    }

    private String extractJsonArray(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private String extractIntent(ChatContext context, String userIntent) {
        if (userIntent != null && !userIntent.isBlank()) return userIntent;
        if (context != null && context.getRequest() != null) {
            String lastMsg = context.getRequest().getLastUserMessage();
            if (lastMsg != null && !lastMsg.isBlank()) return lastMsg;
        }
        return "default task";
    }
}
