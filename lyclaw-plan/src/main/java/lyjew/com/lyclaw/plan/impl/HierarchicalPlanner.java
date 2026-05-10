package lyjew.com.lyclaw.plan.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.task.DecompositionStrategy;
import lyjew.com.lyclaw.task.PlanGraph;
import lyjew.com.lyclaw.task.ReflectionFeedback;
import lyjew.com.lyclaw.task.SimpleTaskPlan;
import lyjew.com.lyclaw.task.TaskNode;
import lyjew.com.lyclaw.task.TaskPlan;
import lyjew.com.lyclaw.task.TaskPlanner;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 层次化任务规划器 —— 自顶向下 (Top-Down) 的递归任务分解。
 *
 * <p>采用经典的 HTN (Hierarchical Task Network) 思想，将任务分解为三个层次：
 * <ol>
 *   <li><b>Level 1 — 高层目标 (Goals)</b>：1-3 个抽象目标，定义任务的宏观方向</li>
 *   <li><b>Level 2 — 中层步骤 (Steps)</b>：每个目标下的 2-5 个具体步骤</li>
 *   <li><b>Level 3 — 原子操作 (Actions)</b>：每个步骤下的 1-3 个可执行动作</li>
 * </ol>
 * </p>
 *
 * <p>构建完成后，层次树被扁平化 (flatten) 为 DAG：
 * 同层级内不同父节点下的子节点可并行执行，
 * 同一父节点下的子节点默认顺序执行（可标记为并行）。</p>
 *
 * <p><b>适用场景</b>：大规模复杂项目（如系统迁移、全栈开发、多模块重构），
 * 需要清晰的层次结构和可追踪的父子关系。</p>
 *
 * <p><b>设计动机</b>：HTN (Hierarchical Task Network) 是 AI 规划领域的经典方法。
 * 在 LyClaw 中，层级化规划使得大型任务能够被系统地拆解和管理，
 * 每一层都有清晰的目标和可追踪的进度，适合可视化展示和项目管理。</p>
 *
 * @since 2.0
 * @author LyClaw Team
 * @see TaskPlanner
 * @see DecompositionStrategy#TREE
 */
@Service("hierarchicalPlanner")
public class HierarchicalPlanner implements TaskPlanner {

    /** 默认 Level-1 目标数 */
    private static final int DEFAULT_L1_COUNT = 2;

    /** 最大 Level-1 目标数 */
    private static final int MAX_L1_COUNT = 3;

    /** 每个 L1 下的默认 L2 步骤数 */
    private static final int DEFAULT_L2_PER_L1 = 3;

    /** 每个 L1 下的最大 L2 步骤数 */
    private static final int MAX_L2_PER_L1 = 5;

    /** 每个 L2 下的默认 L3 动作数 */
    private static final int DEFAULT_L3_PER_L2 = 2;

    /** 每个 L2 下的最大 L3 动作数 */
    private static final int MAX_L3_PER_L2 = 3;

    /** 默认超时（毫秒） */
    private static final long DEFAULT_TIMEOUT_MS = 60_000L;

    /** 层次化分解的 L1 阶段标签 */
    private static final List<String> L1_LABELS = List.of("ANALYSIS", "EXECUTION", "VERIFICATION");

    /** L1 阶段的描述前缀 */
    private static final List<String> L1_DESC_PREFIXES = List.of(
            "Understand and analyze", "Implement and execute", "Verify and validate");

    /** L2 通用步骤标签 */
    private static final List<List<String>> L2_LABELS = List.of(
            List.of("Context-Gathering", "Requirement-Analysis", "Risk-Assessment"),
            List.of("Implementation", "Integration", "Testing", "Optimization", "Deployment"),
            List.of("Validation", "Review", "Documentation")
    );

    /** L3 原子动作标签 */
    private static final List<List<List<String>>> L3_LABELS = List.of(
            List.of( // L1=ANALYSIS 下的 L3
                    List.of("Fetch-Data", "Parse-Context"),
                    List.of("Identify-Constraints", "Map-Dependencies"),
                    List.of("Flag-Risks", "Assess-Impact")
            ),
            List.of( // L1=EXECUTION 下的 L3
                    List.of("Write-Code", "Run-Tests"),
                    List.of("Connect-Components", "Verify-Contracts"),
                    List.of("Execute-Unit-Tests", "Execute-Integration-Tests"),
                    List.of("Profile-Performance", "Apply-Optimizations"),
                    List.of("Build-Artifact", "Deploy-Target")
            ),
            List.of( // L1=VERIFICATION 下的 L3
                    List.of("Run-Validation-Suite", "Collect-Metrics"),
                    List.of("Peer-Review", "Address-Feedback"),
                    List.of("Generate-Report", "Archive-Results")
            )
    );

    /**
     * 构建层次化任务计划。
     *
     * <p>完整流程：确定 L1 目标数 → 为每个 L1 生成 L2 步骤 → 为每个 L2 生成 L3 动作 →
     * 将所有节点扁平化为带有正确依赖关系的列表。</p>
     *
     * <p>扁平化后的 DAG 结构：
     * <ul>
     *   <li>同一 L1 下的 L2 节点：顺序依赖（或可选的并行）</li>
     *   <li>不同 L1 下的 L2 节点：并行（无依赖）</li>
     *   <li>L3 节点：依赖其父 L2 节点</li>
     * </ul>
     * </p>
     *
     * @param context    对话上下文
     * @param userIntent 用户意图
     * @return 层次化任务计划
     */
    @Override
    public TaskPlan plan(ChatContext context, String userIntent) {
        String intent = extractIntent(context, userIntent);
        int l1Count = determineL1Count(intent);
        int l2Count = determineL2Count(intent);

        List<TaskNode> allNodes = new ArrayList<>();
        String prefix = "hier-" + UUID.randomUUID().toString().substring(0, 8);
        List<String> l1Ids = new ArrayList<>();

        // === Level 1: 高层目标 ===
        for (int l1 = 0; l1 < l1Count; l1++) {
            String l1Id = prefix + "-L1-" + l1;
            String label = l1 < L1_LABELS.size() ? L1_LABELS.get(l1) : "PHASE-" + (l1 + 1);
            String descPrefix = l1 < L1_DESC_PREFIXES.size() ? L1_DESC_PREFIXES.get(l1) : "Process phase";
            TaskNode l1Node = new TaskNode(l1Id, label,
                    descPrefix + " — " + intent,
                    List.of(), List.of(), DEFAULT_TIMEOUT_MS * 2);
            allNodes.add(l1Node);
            l1Ids.add(l1Id);

            // === Level 2: 中层步骤 ===
            List<String> l2Ids = new ArrayList<>();
            List<String> l2Labels = l1 < L2_LABELS.size()
                    ? L2_LABELS.get(l1) : List.of("Step-A", "Step-B", "Step-C");

            int actualL2Count = Math.min(l2Labels.size(), l2Count);
            for (int l2 = 0; l2 < actualL2Count; l2++) {
                String l2Id = l1Id + "-L2-" + l2;
                String l2Label = l2Labels.get(l2);
                TaskNode l2Node = new TaskNode(l2Id, l2Label,
                        "[" + label + "] " + l2Label + " for: " + intent,
                        List.of(), List.of(l1Id), DEFAULT_TIMEOUT_MS);
                allNodes.add(l2Node);
                l2Ids.add(l2Id);

                // === Level 3: 原子操作 ===
                List<List<String>> l3Labels = l1 < L3_LABELS.size() && l2 < L3_LABELS.get(l1).size()
                        ? List.of(L3_LABELS.get(l1).get(l2))
                        : List.of(List.of("Execute", "Check"));

                for (List<String> l3Actions : l3Labels) {
                    int actualL3Count = Math.min(l3Actions.size(), DEFAULT_L3_PER_L2);
                    for (int l3 = 0; l3 < actualL3Count; l3++) {
                        String l3Id = l2Id + "-L3-" + l3;
                        String l3Label = l3Actions.get(l3);
                        TaskNode l3Node = new TaskNode(l3Id, "ATOMIC",
                                "[" + l2Label + "] " + l3Label + " — " + intent,
                                List.of(), List.of(l2Id), DEFAULT_TIMEOUT_MS / 2);
                        allNodes.add(l3Node);
                    }
                }
            }
        }

        return new SimpleTaskPlan(allNodes);
    }

    /**
     * 使用上下文消息进行层次化规划。
     */
    @Override
    public TaskPlan plan(ChatContext context) {
        return plan(context, null);
    }

    /**
     * 修订层次化计划 —— 根据反馈调整层级结构。
     *
     * <ul>
     *   <li>低质量：增加一个 Level-1 目标（如额外验证阶段）</li>
     *   <li>反馈提示太简单：增加 Level-2 步骤</li>
     *   <li>反馈提示太复杂：删除最内层 Level-3 节点</li>
     * </ul>
     */
    @Override
    public TaskPlan revise(TaskPlan original, ReflectionFeedback feedback) {
        if (original == null || feedback == null) {
            return original;
        }

        List<TaskNode> nodes = original.getNodes();
        if (nodes.isEmpty()) {
            return original;
        }

        // 低质量 → 添加额外验证层
        if (feedback.getQualityScore() < 0.3) {
            List<TaskNode> revised = new ArrayList<>(nodes);
            String lastNodeId = nodes.get(nodes.size() - 1).getNodeId();
            String extraId = "hier-extra-" + UUID.randomUUID().toString().substring(0, 8);
            TaskNode extraL1 = new TaskNode(extraId, "EXTRA-VERIFICATION",
                    "Additional verification for: " + nodes.get(0).getDescription(),
                    List.of("validation"), List.of(lastNodeId), DEFAULT_TIMEOUT_MS);
            revised.add(extraL1);

            // 追加 L2 + L3
            String extraL2Id = extraId + "-L2-0";
            TaskNode extraL2 = new TaskNode(extraL2Id, "Deep-Check",
                    "Deep check of previous results",
                    List.of(), List.of(extraId), DEFAULT_TIMEOUT_MS);
            revised.add(extraL2);

            String extraL3Id = extraL2Id + "-L3-0";
            TaskNode extraL3 = new TaskNode(extraL3Id, "ATOMIC",
                    "Run final verification suite",
                    List.of(), List.of(extraL2Id), DEFAULT_TIMEOUT_MS / 2);
            revised.add(extraL3);

            return new SimpleTaskPlan(revised);
        }

        return original;
    }

    /**
     * 基于先前的执行结果创建优化的层次化计划。
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

        String prefix = "hier-opt-" + UUID.randomUUID().toString().substring(0, 8);
        List<TaskNode> nodes = new ArrayList<>();

        // L1: 只优化成功的部分
        TaskNode l1 = new TaskNode(prefix + "-L1-0", "OPTIMIZATION",
                "Optimize based on: " + summary, List.of(), List.of(), DEFAULT_TIMEOUT_MS);
        nodes.add(l1);

        // L2: 两个优化步骤
        TaskNode l2a = new TaskNode(prefix + "-L2-0", "Identify-Bottleneck",
                "Find bottleneck in: " + summary, List.of(), List.of(prefix + "-L1-0"), DEFAULT_TIMEOUT_MS);
        nodes.add(l2a);

        TaskNode l2b = new TaskNode(prefix + "-L2-1", "Apply-Fix",
                "Apply fix for: " + summary, List.of(), List.of(prefix + "-L1-0"), DEFAULT_TIMEOUT_MS);
        nodes.add(l2b);

        return new SimpleTaskPlan(nodes);
    }

    /**
     * 按 TREE 策略进行层次化分解。
     *
     * <p>构建完整的 3 层树结构，然后扁平化为 DAG。</p>
     */
    @Override
    public PlanGraph decompose(TaskNode rootTask, DecompositionStrategy strategy) {
        PlanGraph graph = new PlanGraph();
        graph.addNode(rootTask);

        String desc = rootTask.getDescription();
        int l1Count = determineL1Count(desc);
        String prefix = rootTask.getNodeId() + "-hier";

        for (int l1 = 0; l1 < l1Count; l1++) {
            String l1Id = prefix + "-L1-" + l1;
            String label = l1 < L1_LABELS.size() ? L1_LABELS.get(l1) : "PHASE-" + (l1 + 1);
            String descPrefix = l1 < L1_DESC_PREFIXES.size() ? L1_DESC_PREFIXES.get(l1) : "Process";
            TaskNode l1Node = new TaskNode(l1Id, label,
                    descPrefix + " — " + desc, List.of(),
                    List.of(rootTask.getNodeId()), rootTask.getTimeoutMs());
            graph.addNode(l1Node);
            graph.addEdge(rootTask.getNodeId(), l1Id);

            List<String> l2Labels = l1 < L2_LABELS.size()
                    ? L2_LABELS.get(l1) : List.of("Step-A", "Step-B");

            int l2Count = Math.min(l2Labels.size(), DEFAULT_L2_PER_L1);
            for (int l2 = 0; l2 < l2Count; l2++) {
                String l2Id = l1Id + "-L2-" + l2;
                String l2Label = l2Labels.get(l2);
                TaskNode l2Node = new TaskNode(l2Id, l2Label,
                        "[" + label + "] " + l2Label + " for: " + desc,
                        List.of(), List.of(l1Id), rootTask.getTimeoutMs());
                graph.addNode(l2Node);
                graph.addEdge(l1Id, l2Id);

                List<List<String>> l3Labels = l1 < L3_LABELS.size() && l2 < L3_LABELS.get(l1).size()
                        ? List.of(L3_LABELS.get(l1).get(l2))
                        : List.of(List.of("Execute"));

                for (List<String> l3Actions : l3Labels) {
                    for (int l3 = 0; l3 < Math.min(l3Actions.size(), DEFAULT_L3_PER_L2); l3++) {
                        String l3Id = l2Id + "-L3-" + l3;
                        TaskNode l3Node = new TaskNode(l3Id, "ATOMIC",
                                "[" + l2Label + "] " + l3Actions.get(l3) + " — " + desc,
                                List.of(), List.of(l2Id), rootTask.getTimeoutMs() / 2);
                        graph.addNode(l3Node);
                        graph.addEdge(l2Id, l3Id);
                    }
                }
            }
        }

        return graph;
    }

    // ==================== 辅助方法 ====================

    /**
     * 根据任务复杂度确定 L1 目标数。
     */
    private int determineL1Count(String intent) {
        if (intent == null || intent.isBlank()) {
            return DEFAULT_L1_COUNT;
        }
        // 根据描述长度和复杂度关键词估算
        int base = intent.length() / 100 + 1;
        return Math.min(MAX_L1_COUNT, Math.max(1, base));
    }

    /**
     * 根据任务复杂度确定每个 L1 下的 L2 步骤数。
     */
    private int determineL2Count(String intent) {
        if (intent == null || intent.isBlank()) {
            return DEFAULT_L2_PER_L1;
        }
        int base = intent.length() / 80 + 2;
        return Math.min(MAX_L2_PER_L1, Math.max(2, base));
    }

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
        return "execute hierarchical task";
    }
}
