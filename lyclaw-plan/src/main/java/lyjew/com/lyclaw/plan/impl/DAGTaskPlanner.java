package lyjew.com.lyclaw.plan.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.task.DecompositionStrategy;
import lyjew.com.lyclaw.task.PlanGraph;
import lyjew.com.lyclaw.task.ReflectionFeedback;
import lyjew.com.lyclaw.task.SimpleTaskPlan;
import lyjew.com.lyclaw.task.TaskNode;
import lyjew.com.lyclaw.task.AbstractTaskPlanner;
import lyjew.com.lyclaw.task.TaskDecomposer;
import lyjew.com.lyclaw.task.TaskPlan;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * DAG (有向无环图) 任务规划器 —— 基于任务复杂度的智能规划引擎。
 *
 * <p>将用户意图解析为复杂度级别，根据复杂度生成不同粒度的任务计划：
 * <ul>
 *   <li><b>简单任务</b>：单个节点的线性计划</li>
 *   <li><b>中等任务</b>：按阶段分解的 DAG 计划</li>
 *   <li><b>复杂任务</b>：多维度分解的并行 DAG 计划</li>
 * </ul>
 * </p>
 *
 * <p>支持计划修订：根据反思反馈重新规划、重排序节点或插入缺失步骤。</p>
 *
 * <p><b>设计动机</b>：相较于 DefaultTaskPlanner 的贪心线性策略，
 * DAGTaskPlanner 能够识别任务复杂度并构建真正的有向无环图，
 * 使得无依赖关系的子任务可以并行执行，提升整体吞吐量。</p>
 *
 * @since 2.0
 * @author LyClaw Team
 * @see TaskPlanner
 * @see TaskPlan
 * @see PlanGraph
 */
@Service
public class DAGTaskPlanner extends AbstractTaskPlanner {

    private final TaskDecomposer taskDecomposer;

    public DAGTaskPlanner(TaskDecomposer taskDecomposer) {
        this.taskDecomposer = taskDecomposer;
    }

    /** 简单任务的复杂度阈值（关键词命中数 <= 此值视为简单） */
    private static final int SIMPLE_THRESHOLD = 1;

    /** 中等任务的复杂度阈值 */
    private static final int MEDIUM_THRESHOLD = 3;

    /** 复杂度关键词模式：匹配复杂操作动词 */
    private static final Pattern COMPLEX_PATTERN = Pattern.compile(
            "(?i)\\b(build|create|develop|implement|design|deploy|migrate|refactor|"
                    + "optimize|integrate|configure|orchestrate|analyze|investigate|"
                    + "transform|generate|compare|evaluate|summarize|translate|review|"
                    + "同时|并行|并且|以及|另外|此外|首先.*然后.*最后|"
                    + "第一步|第二步|第三步|阶段|步骤)\\b");

    /** 简单任务关键词 */
    private static final Pattern SIMPLE_PATTERN = Pattern.compile(
            "(?i)\\b(what is|who is|when|where|how to|define|explain|describe|list|show|get|fetch)\\b");

    /** 默认任务超时时间（毫秒） */
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    /** 单节点超时 */
    private static final long SIMPLE_TIMEOUT_MS = 10_000L;

    /** 计划中允许的最大节点数 */
    private static final int MAX_NODES = 50;

    /**
     * 根据用户意图进行 DAG 规划。
     *
     * @param context    对话上下文
     * @param userIntent 用户意图文本
     * @return 包含 DAG 任务节点的计划
     */
    @Override
    public TaskPlan plan(ChatContext context, String userIntent) {
        String intent = extractIntent(context, userIntent);
        int complexity = assessComplexity(intent);

        return switch (complexity) {
            case 0, 1 -> buildSimplePlan(intent);
            case 2, 3 -> buildMediumPlan(intent);
            default -> buildComplexPlan(intent);
        };
    }

    /**
     * 使用上下文中的最后一条用户消息进行规划。
     *
     * @param context 对话上下文
     * @return 任务计划
     */
    @Override
    public TaskPlan plan(ChatContext context) {
        return plan(context, null);
    }

    /**
     * 根据反思反馈修订现有计划。
     *
     * <p>修订策略：
     * <ul>
     *   <li>feedback.suggestedStrategy 包含 "replan" 或 qualityScore < 0.3：重新规划</li>
     *   <li>feedback.suggestedStrategy 包含 "reorder"：重新排序节点</li>
     *   <li>feedback.suggestedStrategy 包含 "insert" 或 detectedErrors 提及缺失步骤：插入节点</li>
     * </ul>
     * </p>
     *
     * @param original 原始计划
     * @param feedback 反思反馈
     * @return 修订后的计划，如果无修订需要则返回原计划
     */
    @Override
    public TaskPlan revise(TaskPlan original, ReflectionFeedback feedback) {
        if (original == null || feedback == null) {
            return original;
        }

        String strategy = feedback.getSuggestedStrategy() != null
                ? feedback.getSuggestedStrategy().toLowerCase() : "";

        // 低质量分数 → 完全重新规划
        if (feedback.getQualityScore() < 0.3 || strategy.contains("replan")) {
            return fullReplan(original, feedback);
        }

        // 顺序变更 → 重排序
        if (strategy.contains("reorder") || hasOrderErrors(feedback)) {
            return reorderNodes(original, feedback);
        }

        // 缺失步骤 → 插入节点
        if (strategy.contains("insert") || hasMissingSteps(feedback)) {
            return insertNode(original, feedback);
        }

        return original;
    }

    /**
     * 优化先前结果 —— 基于执行结果生成优化计划。
     *
     * @param previousResult 之前的 Agent 执行结果
     * @return 优化计划，若无法优化则返回 null
     */
    @Override
    public TaskPlan optimize(AgentResult previousResult) {
        if (previousResult == null) {
            return null;
        }

        String summary = previousResult.getSummary();
        if (summary == null || summary.isBlank()) {
            return null;
        }

        String nodeId = "opt-" + UUID.randomUUID().toString().substring(0, 8);
        TaskNode optimizeNode = new TaskNode(
                nodeId, "OPTIMIZE",
                "Optimize based on previous result: " + summary,
                List.of(), List.of(), DEFAULT_TIMEOUT_MS);

        return new SimpleTaskPlan(List.of(optimizeNode));
    }

    /**
     * 使用指定策略分解根任务为 DAG 子图。
     *
     * <p>各策略行为：
     * <ul>
     *   <li><b>SEQUENTIAL</b>：按顺序 A→B→C 分解为线性链</li>
     *   <li><b>BY_DOMAIN</b>：按知识领域分组，同领域内串行、不同领域间并行</li>
     *   <li><b>BY_PHASE</b>：分析→设计→实现→验证 四阶段流水线</li>
     *   <li><b>PARALLEL_INDEPENDENT</b>：识别独立子任务并全部并行</li>
     *   <li><b>LLM_DRIVEN / TREE</b>：回退到 TaskDecomposer 或递归分解</li>
     * </ul>
     * </p>
     *
     * @param rootTask 根任务节点
     * @param strategy 分解策略
     * @return 包含子节点的 PlanGraph
     */
    @Override
    public PlanGraph decompose(TaskNode rootTask, DecompositionStrategy strategy) {
        if (rootTask == null || strategy == null) {
            PlanGraph graph = new PlanGraph();
            if (rootTask != null) {
                graph.addNode(rootTask);
            }
            return graph;
        }

        return switch (strategy) {
            case SEQUENTIAL -> decomposeSequential(rootTask);
            case BY_DOMAIN -> decomposeByDomain(rootTask);
            case BY_PHASE -> decomposeByPhase(rootTask);
            case PARALLEL_INDEPENDENT -> decomposeParallel(rootTask);
            case LLM_DRIVEN -> decomposeLlmDriven(rootTask);
            case TREE -> decomposeTree(rootTask);
        };
    }

    // ==================== 复杂度评估 ====================

    /**
     * 评估任务复杂度等级。
     *
     * @param intent 用户意图文本
     * @return 复杂度等级：0-1简单，2-3中等，4+复杂
     */
    private int assessComplexity(String intent) {
        if (intent == null || intent.isBlank()) {
            return 0;
        }

        long complexCount = COMPLEX_PATTERN.matcher(intent).results().count();
        boolean isSimple = SIMPLE_PATTERN.matcher(intent).find();

        if (isSimple && complexCount <= SIMPLE_THRESHOLD) {
            return 0;
        }
        if (complexCount <= SIMPLE_THRESHOLD) {
            return 1;
        }
        if (complexCount <= MEDIUM_THRESHOLD) {
            return 2;
        }
        return 4;
    }

    // ==================== 计划构建 ====================

    /**
     * 构建简单任务计划 —— 单节点。
     */
    private TaskPlan buildSimplePlan(String intent) {
        String nodeId = "task-" + UUID.randomUUID().toString().substring(0, 8);
        TaskNode node = new TaskNode(nodeId, "EXECUTE", intent,
                List.of(), List.of(), SIMPLE_TIMEOUT_MS);
        return new SimpleTaskPlan(List.of(node));
    }

    /**
     * 构建中等复杂度计划 —— 按阶段分解的线性 DAG。
     *
     * <p>阶段：ANALYZE → PLAN → EXECUTE → VERIFY</p>
     */
    private TaskPlan buildMediumPlan(String intent) {
        String prefix = "mid-" + UUID.randomUUID().toString().substring(0, 8);
        List<TaskNode> nodes = new ArrayList<>();

        TaskNode analyze = new TaskNode(prefix + "-ana", "ANALYZE",
                "Analyze: " + intent, List.of("knowledge_search"),
                List.of(), DEFAULT_TIMEOUT_MS);
        nodes.add(analyze);

        TaskNode planStep = new TaskNode(prefix + "-pln", "PLAN",
                "Plan approach for: " + intent, List.of(),
                List.of(analyze.getNodeId()), DEFAULT_TIMEOUT_MS);
        nodes.add(planStep);

        TaskNode execute = new TaskNode(prefix + "-exe", "EXECUTE",
                "Execute: " + intent, List.of(),
                List.of(planStep.getNodeId()), DEFAULT_TIMEOUT_MS);
        nodes.add(execute);

        TaskNode verify = new TaskNode(prefix + "-vfy", "VERIFY",
                "Verify result of: " + intent, List.of(),
                List.of(execute.getNodeId()), DEFAULT_TIMEOUT_MS);
        nodes.add(verify);

        return new SimpleTaskPlan(nodes);
    }

    /**
     * 构建复杂任务计划 —— 多分支并行 DAG。
     *
     * <p>结构：
     * <pre>
     *        ┌─── parallel-a ───┐
     * root ──┼─── parallel-b ───┼── merge
     *        └─── parallel-c ───┘
     * </pre>
     * </p>
     */
    private TaskPlan buildComplexPlan(String intent) {
        String prefix = "cx-" + UUID.randomUUID().toString().substring(0, 8);
        List<TaskNode> nodes = new ArrayList<>();

        // 根节点：分析理解
        TaskNode root = new TaskNode(prefix + "-root", "ANALYZE",
                "Analyze complex task: " + intent,
                List.of("knowledge_search"), List.of(), DEFAULT_TIMEOUT_MS);
        nodes.add(root);

        // 并行分支
        TaskNode branchA = new TaskNode(prefix + "-a", "RESEARCH",
                "Research domain knowledge for: " + intent,
                List.of("web_search"), List.of(root.getNodeId()), DEFAULT_TIMEOUT_MS);
        nodes.add(branchA);

        TaskNode branchB = new TaskNode(prefix + "-b", "DESIGN",
                "Design solution architecture for: " + intent,
                List.of(), List.of(root.getNodeId()), DEFAULT_TIMEOUT_MS);
        nodes.add(branchB);

        TaskNode branchC = new TaskNode(prefix + "-c", "PREPARE",
                "Prepare resources for: " + intent,
                List.of("file_read"), List.of(root.getNodeId()), DEFAULT_TIMEOUT_MS);
        nodes.add(branchC);

        // 合并节点：依赖所有并行分支
        TaskNode merge = new TaskNode(prefix + "-merge", "INTEGRATE",
                "Integrate results for: " + intent,
                List.of(), List.of(branchA.getNodeId(), branchB.getNodeId(), branchC.getNodeId()),
                DEFAULT_TIMEOUT_MS);
        nodes.add(merge);

        // 最终执行
        TaskNode execute = new TaskNode(prefix + "-exe", "EXECUTE",
                "Execute integrated plan: " + intent,
                List.of(), List.of(merge.getNodeId()), DEFAULT_TIMEOUT_MS * 2);
        nodes.add(execute);

        // 验证
        TaskNode verify = new TaskNode(prefix + "-vfy", "VERIFY",
                "Verify complete result for: " + intent,
                List.of(), List.of(execute.getNodeId()), DEFAULT_TIMEOUT_MS);
        nodes.add(verify);

        return new SimpleTaskPlan(nodes);
    }

    // ==================== 计划修订 ====================

    /**
     * 完全重新规划。
     */
    private TaskPlan fullReplan(TaskPlan original, ReflectionFeedback feedback) {
        String adjustedPrompt = feedback.getAdjustedPrompt();
        if (adjustedPrompt != null && !adjustedPrompt.isBlank()) {
            // 使用调整后的提示重新规划
            return buildMediumPlan(adjustedPrompt);
        }

        // 使用原始节点描述重新规划
        List<TaskNode> nodes = original.getNodes();
        if (nodes != null && !nodes.isEmpty()) {
            String rootDesc = nodes.get(0).getDescription();
            return buildMediumPlan(rootDesc);
        }
        return original;
    }

    /**
     * 重新排序节点 —— 反转顺序或按建议调整。
     */
    private TaskPlan reorderNodes(TaskPlan original, ReflectionFeedback feedback) {
        List<TaskNode> nodes = new ArrayList<>(original.getNodes());
        if (nodes.size() <= 1) {
            return original;
        }

        // 基于反馈的简单重排序：如果反馈建议重排序，反转节点顺序作为替代方案
        List<TaskNode> reordered = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            TaskNode old = nodes.get(i);
            List<String> newDeps = new ArrayList<>();
            // 新依赖关系：后面的节点依赖前面的
            if (i > 0) {
                newDeps.add(nodes.get(i - 1).getNodeId());
            }
            TaskNode reorderedNode = new TaskNode(
                    old.getNodeId() + "-v2", old.getType(), old.getDescription(),
                    old.getRequiredTools(), newDeps, old.getTimeoutMs());
            reordered.add(reorderedNode);
        }

        return new SimpleTaskPlan(reordered);
    }

    /**
     * 插入缺失节点。
     */
    private TaskPlan insertNode(TaskPlan original, ReflectionFeedback feedback) {
        List<TaskNode> nodes = new ArrayList<>(original.getNodes());
        if (nodes.isEmpty()) {
            return original;
        }

        String adjustedPrompt = feedback.getAdjustedPrompt();
        String insertDesc = (adjustedPrompt != null && !adjustedPrompt.isBlank())
                ? "Missing step: " + adjustedPrompt
                : "Additional validation step";

        // 在倒数第二个位置插入（最后一个节点之前）
        int insertPos = Math.max(0, nodes.size() - 1);
        String insertId = "ins-" + UUID.randomUUID().toString().substring(0, 8);

        // 新节点依赖前一个节点
        String prevId = insertPos > 0 ? nodes.get(insertPos - 1).getNodeId() : null;
        List<String> deps = prevId != null ? List.of(prevId) : List.of();

        TaskNode inserted = new TaskNode(insertId, "VERIFY", insertDesc,
                List.of("validation"), deps, DEFAULT_TIMEOUT_MS);
        nodes.add(insertPos, inserted);

        // 更新后续节点的依赖
        for (int i = insertPos + 1; i < nodes.size(); i++) {
            TaskNode old = nodes.get(i);
            List<String> updatedDeps = new ArrayList<>(old.getDependencies());
            updatedDeps.add(inserted.getNodeId());
            TaskNode updated = new TaskNode(old.getNodeId(), old.getType(),
                    old.getDescription(), old.getRequiredTools(),
                    updatedDeps, old.getTimeoutMs());
            nodes.set(i, updated);
        }

        return new SimpleTaskPlan(nodes);
    }

    private boolean hasOrderErrors(ReflectionFeedback feedback) {
        if (feedback.getDetectedErrors() == null) return false;
        return feedback.getDetectedErrors().stream()
                .anyMatch(e -> e.toLowerCase().contains("order") || e.toLowerCase().contains("sequence"));
    }

    private boolean hasMissingSteps(ReflectionFeedback feedback) {
        if (feedback.getDetectedErrors() == null) return false;
        return feedback.getDetectedErrors().stream()
                .anyMatch(e -> e.toLowerCase().contains("missing") || e.toLowerCase().contains("缺少"));
    }

    // ==================== 分解策略实现 ====================

    /**
     * 顺序分解：将根任务按句子边界拆分为 3-5 个顺序子任务。
     */
    private PlanGraph decomposeSequential(TaskNode rootTask) {
        PlanGraph graph = new PlanGraph();
        graph.addNode(rootTask);

        String desc = rootTask.getDescription();
        String[] sentences = desc.split("[。.!！?？\n;；]");
        List<String> parts = new ArrayList<>();
        for (String s : sentences) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }

        if (parts.isEmpty()) {
            parts.add(desc);
        }

        // 如果自然拆分过多，合并为 3-5 组
        if (parts.size() > 5) {
            List<String> merged = new ArrayList<>();
            int groupSize = (int) Math.ceil((double) parts.size() / 4);
            for (int i = 0; i < parts.size(); i += groupSize) {
                int end = Math.min(i + groupSize, parts.size());
                merged.add(String.join("; ", parts.subList(i, end)));
            }
            parts = merged;
        }

        String prefix = rootTask.getNodeId() + "-seq";
        String prevId = rootTask.getNodeId();

        for (int i = 0; i < parts.size(); i++) {
            String childId = prefix + "-" + i;
            TaskNode child = new TaskNode(childId, "EXECUTE", parts.get(i),
                    rootTask.getRequiredTools(),
                    List.of(prevId), rootTask.getTimeoutMs());
            graph.addNode(child);
            graph.addEdge(prevId, childId);
            prevId = childId;
        }

        return graph;
    }

    /**
     * 按领域分解：识别描述中的不同知识领域并分组。
     *
     * <p>领域映射关键词：代码/编程、数据/数据库、文档/文本、网络/API。</p>
     */
    private PlanGraph decomposeByDomain(TaskNode rootTask) {
        PlanGraph graph = new PlanGraph();
        graph.addNode(rootTask);

        String desc = rootTask.getDescription().toLowerCase();

        // 领域 → 关键词映射
        Map<String, String> domainKeywords = new LinkedHashMap<>();
        domainKeywords.put("code", "(code|编程|代码|program|function|class|method|接口|api|impl)");
        domainKeywords.put("data", "(data|数据|database|数据库|sql|query|table|schema|存储)");
        domainKeywords.put("document", "(doc|文档|documentation|readme|write|撰写|编写|说明)");
        domainKeywords.put("network", "(network|网络|http|request|response|endpoint|服务|deploy|部署)");

        String prefix = rootTask.getNodeId() + "-dom";
        int idx = 0;
        String prevId = rootTask.getNodeId();

        for (Map.Entry<String, String> entry : domainKeywords.entrySet()) {
            if (Pattern.compile(entry.getValue()).matcher(desc).find()) {
                String childId = prefix + "-" + idx;
                TaskNode child = new TaskNode(childId, entry.getKey().toUpperCase(),
                        "Handle " + entry.getKey() + " aspects: " + rootTask.getDescription(),
                        List.of(), List.of(prevId), rootTask.getTimeoutMs());
                graph.addNode(child);
                graph.addEdge(prevId, childId);
                prevId = childId;
                idx++;
            }
        }

        // 如果没有匹配到任何领域，创建一个通用的领域节点
        if (idx == 0) {
            String childId = prefix + "-general";
            TaskNode child = new TaskNode(childId, "GENERAL",
                    "General processing: " + rootTask.getDescription(),
                    List.of(), List.of(prevId), rootTask.getTimeoutMs());
            graph.addNode(child);
            graph.addEdge(prevId, childId);
        }

        return graph;
    }

    /**
     * 按阶段分解：分析 → 设计 → 实现 → 验证。
     *
     * <p>这是经典的软件工程流水线分解模式。</p>
     */
    private PlanGraph decomposeByPhase(TaskNode rootTask) {
        PlanGraph graph = new PlanGraph();
        graph.addNode(rootTask);

        String prefix = rootTask.getNodeId() + "-phs";
        String desc = rootTask.getDescription();

        String[] phases = {"ANALYZE", "DESIGN", "IMPLEMENT", "VERIFY"};
        String[] phaseDescs = {
                "Analyze requirements and constraints for: ",
                "Design solution approach for: ",
                "Implement the solution for: ",
                "Verify and validate results for: "
        };

        String prevId = rootTask.getNodeId();
        for (int i = 0; i < phases.length; i++) {
            String childId = prefix + "-" + i;
            TaskNode child = new TaskNode(childId, phases[i],
                    phaseDescs[i] + desc,
                    i == 2 ? List.of("code_executor") : List.of(), // IMPLEMENT 阶段需要工具
                    List.of(prevId), rootTask.getTimeoutMs());
            graph.addNode(child);
            graph.addEdge(prevId, childId);
            prevId = childId;
        }

        return graph;
    }

    /**
     * 并行独立分解：识别可独立执行的子任务并全部并行化。
     *
     * <p>检测独立信号词（同时、并行、分别）后，将内容拆分为并行分支。</p>
     */
    private PlanGraph decomposeParallel(TaskNode rootTask) {
        PlanGraph graph = new PlanGraph();
        graph.addNode(rootTask);

        String desc = rootTask.getDescription();

        // === 阶段1：扫描独立信号词，判断任务是否包含可并行的子任务 ===
        // 通过检测中英文独立信号词（如"同时"、"并行"、"also"、"in parallel"等）
        // 来确定任务描述中是否明确表达了多任务并行执行的意图。
        String[] independentMarkers = {"同时", "并行", "分别", "一方面", "另一方面",
                "also", "meanwhile", "in parallel", "separately", "respectively"};

        String prefix = rootTask.getNodeId() + "-par";
        boolean hasIndependent = false;

        // 逐一遍历所有独立信号词，只要命中任意一个即视为存在并行意图
        for (String marker : independentMarkers) {
            if (desc.toLowerCase().contains(marker.toLowerCase())) {
                hasIndependent = true;
                break;  // 提前终止，无需检查剩余信号词
            }
        }

        // === 分支1：任务包含独立信号词 → 按标点分割并拆分为并行子任务 ===
        if (hasIndependent) {
            // 阶段2a：按逗号、分号等分隔符将任务描述拆分为多个独立子句
            // 每个子句将成为一个并行分支的描述文本
            String[] splitByComma = desc.split("[,，;；]");
            List<String> branches = new ArrayList<>();
            for (String s : splitByComma) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty()) {
                    branches.add(trimmed);
                }
            }

            // 阶段2b：兜底处理 — 如果按标点拆分后只有1个或0个分支，
            // 说明任务描述中没有明显的分句结构，此时手动构造两个通用分支
            if (branches.size() < 2) {
                branches = List.of(
                        "Part A of: " + desc,
                        "Part B of: " + desc);
            }

            // 阶段2c：分支数量上限控制 — 当拆分后的分支数超过5个时，
            // 按平均分组策略将多余的分支合并到5个组中，避免并行度过高
            // 导致资源竞争和调度复杂度失控
            if (branches.size() > 5) {
                List<String> merged = new ArrayList<>();
                int groupSize = (int) Math.ceil((double) branches.size() / 5);
                for (int i = 0; i < branches.size(); i += groupSize) {
                    int end = Math.min(i + groupSize, branches.size());
                    merged.add(String.join("; ", branches.subList(i, end)));
                }
                branches = merged;
            }

            // 阶段2d：将每个分支创建为独立的任务节点并加入图中
            // 所有分支节点都依赖根任务节点（rootTask），形成扇出结构
            // 分支节点之间彼此无依赖关系，因此可以被并行调度执行
            for (int i = 0; i < branches.size(); i++) {
                String childId = prefix + "-" + i;
                TaskNode child = new TaskNode(childId, "EXECUTE", branches.get(i),
                        rootTask.getRequiredTools(),
                        List.of(rootTask.getNodeId()), rootTask.getTimeoutMs());
                graph.addNode(child);
                graph.addEdge(rootTask.getNodeId(), childId);
            }
        } else {
            // === 分支2：任务不包含独立信号词 → 创建两个通用并行分析节点 ===
            // 即使没有显式并行标记，仍然构造两个并行的分析节点作为默认策略，
            // 分别从不同角度（Aspect A / Aspect B）分析同一任务，
            // 这样可以提供多视角的分析结果，后续可通过合并节点整合
            TaskNode childA = new TaskNode(prefix + "-a", "ANALYZE",
                    "Analyze aspect A: " + desc, List.of(),
                    List.of(rootTask.getNodeId()), rootTask.getTimeoutMs());
            TaskNode childB = new TaskNode(prefix + "-b", "ANALYZE",
                    "Analyze aspect B: " + desc, List.of(),
                    List.of(rootTask.getNodeId()), rootTask.getTimeoutMs());
            graph.addNode(childA);
            graph.addNode(childB);
            graph.addEdge(rootTask.getNodeId(), prefix + "-a");
            graph.addEdge(rootTask.getNodeId(), prefix + "-b");
        }

        return graph;
    }

    /**
     * LLM 驱动分解：委托给 TaskDecomposer（如果可用）。
     * 当前回退为按阶段分解。
     */
    private PlanGraph decomposeLlmDriven(TaskNode rootTask) {
        PlanGraph graph = new PlanGraph();
        graph.addNode(rootTask);

        List<TaskNode> subtasks = taskDecomposer.decompose(
                rootTask.getDescription(), DecompositionStrategy.LLM_DRIVEN);
        for (TaskNode sub : subtasks) {
            graph.addNode(sub);
            if (sub.getDependencies() != null) {
                for (String dep : sub.getDependencies()) {
                    graph.addEdge(dep, sub.getNodeId());
                }
            }
        }
        return graph;
    }

    /**
     * 树形递归分解：自顶向下递归拆分为 2 层深度。
     */
    private PlanGraph decomposeTree(TaskNode rootTask) {
        PlanGraph graph = new PlanGraph();
        graph.addNode(rootTask);

        String prefix = rootTask.getNodeId() + "-tree";

        // 第一层：3 个子节点（并行）
        for (int i = 0; i < 3; i++) {
            String l1Id = prefix + "-L1-" + i;
            TaskNode l1 = new TaskNode(l1Id, "DECOMPOSE",
                    "Level-1 subtask " + (i + 1) + " of: " + rootTask.getDescription(),
                    List.of(), List.of(rootTask.getNodeId()), rootTask.getTimeoutMs());
            graph.addNode(l1);
            graph.addEdge(rootTask.getNodeId(), l1Id);

            // 第二层：每个 L1 节点下挂 2 个子节点
            for (int j = 0; j < 2; j++) {
                String l2Id = l1Id + "-L2-" + j;
                TaskNode l2 = new TaskNode(l2Id, "EXECUTE",
                        "Level-2 subtask " + (j + 1) + " under " + l1.getDescription(),
                        List.of(), List.of(l1Id), rootTask.getTimeoutMs());
                graph.addNode(l2);
                graph.addEdge(l1Id, l2Id);
            }
        }

        return graph;
    }

    // ==================== 辅助方法 ====================

    /**
     * 从上下文或用户意图中提取任务描述。
     */
    private String extractIntent(ChatContext context, String userIntent) {
        if (userIntent != null && !userIntent.isBlank()) {
            return userIntent;
        }
        if (context != null && context.getRequest() != null) {
            String lastMsg = context.getRequest().getLastUserMessage();
            if (lastMsg != null && !lastMsg.isBlank()) {
                return lastMsg;
            }
        }
        return "default task";
    }
}
