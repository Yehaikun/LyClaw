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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 链式思考 (Chain-of-Thought) 规划器 —— 将推理过程分解为逐步执行的任务序列。
 *
 * <p>CoT 规划器模仿人类"逐步推理"的思维模式，将复杂问题拆解为：
 * <ol>
 *   <li><b>THINK</b> — 推理步骤（理解问题、分析条件、推理结论）</li>
 *   <li><b>ACT</b>   — 行动步骤（执行工具调用、获取数据）</li>
 *   <li><b>OBSERVE</b> — 观察步骤（检查结果、验证假设）</li>
 * </ol>
 * </p>
 *
 * <p>每一步都是链中的一个节点，前一步的输出是后一步的输入（通过依赖关系保证），
 * 形成一个严格的顺序推理链条。</p>
 *
 * <p><b>适用场景</b>：数学推理、逻辑推理、代码调试、复杂决策分析。</p>
 *
 * <p><b>设计动机</b>：CoT 是目前大模型推理中最有效的方法之一。
 * 将 CoT 思维链映射到任务计划 DAG，使得推理过程可追踪、可中断、可复盘。
 * 相较于贪心规划器，CoTPlanner 生成的是有结构的推理树而非简单步骤列表。</p>
 *
 * @since 2.0
 * @author LyClaw Team
 * @see TaskPlanner
 * @see DecompositionStrategy#SEQUENTIAL
 */
@Service("cotPlanner")
public class CoTPlanner implements TaskPlanner {

    /** 关键词模式：识别需要链式推理的任务 */
    private static final Pattern REASONING_PATTERN = Pattern.compile(
            "(?i)\\b(why|reason|explain step|step by step|逻辑|推理|"
                    + "推导|证明|计算|solve|prove|derive|analyze deep|"
                    + "debug|trace|diagnose|根因|root cause)\\b");

    /** 默认推理链最大长度 */
    private static final int DEFAULT_MAX_CHAIN_LENGTH = 5;

    /** 默认超时 */
    private static final long DEFAULT_TIMEOUT_MS = 60_000L;

    /**
     * 构建链式思考任务计划。
     *
     * <p>识别用户意图中的推理需求，构建 Think → Act → Observe 循环链。
     * 简单问题使用 3 步链，复杂问题使用最多 5 步链。</p>
     *
     * @param context    对话上下文
     * @param userIntent 用户意图
     * @return 链式任务计划
     */
    @Override
    public TaskPlan plan(ChatContext context, String userIntent) {
        String intent = extractIntent(context, userIntent);
        int chainLength = determineChainLength(intent);

        List<TaskNode> nodes = new ArrayList<>();
        String prefix = "cot-" + UUID.randomUUID().toString().substring(0, 8);
        String prevId = null;

        for (int i = 0; i < chainLength; i++) {
            CoTStep step = determineStep(intent, i, chainLength);
            String nodeId = prefix + "-step" + i;

            List<String> deps = prevId != null ? List.of(prevId) : List.of();
            List<String> tools = step.requiresTool() ? List.of("knowledge_search") : List.of();

            TaskNode node = new TaskNode(nodeId, step.name(),
                    step.buildDescription(intent, i + 1, chainLength),
                    tools, deps, DEFAULT_TIMEOUT_MS);
            nodes.add(node);
            prevId = nodeId;
        }

        return new SimpleTaskPlan(nodes);
    }

    /**
     * 使用上下文中的最后一条消息进行 CoT 规划。
     */
    @Override
    public TaskPlan plan(ChatContext context) {
        return plan(context, null);
    }

    /**
     * 修订 CoT 计划 —— 根据反馈调整推理链。
     *
     * <ul>
     *   <li>质量分 < 0.3：重新生成完整推理链</li>
     *   <li>反馈建议额外步骤：在链中插入新的观察/验证步骤</li>
     *   <li>反馈建议跳过步骤：缩减链长度</li>
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

        String rootDesc = nodes.get(0).getDescription();

        // 低质量 → 完全重建
        if (feedback.getQualityScore() < 0.3) {
            return plan(null, rootDesc);
        }

        // 建议插入步骤
        String strategy = feedback.getSuggestedStrategy() != null
                ? feedback.getSuggestedStrategy().toLowerCase() : "";
        if (strategy.contains("add_step") || strategy.contains("verify")) {
            List<TaskNode> newNodes = new ArrayList<>(nodes);
            String lastId = nodes.get(nodes.size() - 1).getNodeId();
            String insertId = "cot-vfy-" + UUID.randomUUID().toString().substring(0, 8);
            TaskNode verify = new TaskNode(insertId, "OBSERVE",
                    "Verify CoT reasoning for: " + rootDesc,
                    List.of("validation"), List.of(lastId), DEFAULT_TIMEOUT_MS);
            newNodes.add(verify);
            return new SimpleTaskPlan(newNodes);
        }

        return original;
    }

    /**
     * 基于先前的执行结果优化 CoT 计划。
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

        String prefix = "cot-opt-" + UUID.randomUUID().toString().substring(0, 8);
        List<TaskNode> nodes = new ArrayList<>();

        nodes.add(new TaskNode(prefix + "-think", "THINK",
                "Reflect on previous result: " + summary,
                List.of(), List.of(), DEFAULT_TIMEOUT_MS));
        nodes.add(new TaskNode(prefix + "-act", "ACT",
                "Apply optimization based on: " + summary,
                List.of(), List.of(prefix + "-think"), DEFAULT_TIMEOUT_MS));
        nodes.add(new TaskNode(prefix + "-observe", "OBSERVE",
                "Verify optimization effect: " + summary,
                List.of(), List.of(prefix + "-act"), DEFAULT_TIMEOUT_MS));

        return new SimpleTaskPlan(nodes);
    }

    /**
     * 按 CoT 策略分解根任务 —— 等同于 SEQUENTIAL 策略（因为 CoT 本身就是顺序链）。
     */
    @Override
    public PlanGraph decompose(TaskNode rootTask, DecompositionStrategy strategy) {
        PlanGraph graph = new PlanGraph();
        graph.addNode(rootTask);

        String desc = rootTask.getDescription();
        int chainLength = determineChainLength(desc);

        String prefix = rootTask.getNodeId() + "-cot";
        String prevId = rootTask.getNodeId();

        for (int i = 0; i < chainLength; i++) {
            CoTStep step = determineStep(desc, i, chainLength);
            String childId = prefix + "-" + i;
            TaskNode child = new TaskNode(childId, step.name(),
                    step.buildDescription(desc, i + 1, chainLength),
                    List.of(), List.of(prevId), rootTask.getTimeoutMs());
            graph.addNode(child);
            graph.addEdge(prevId, childId);
            prevId = childId;
        }

        return graph;
    }

    // ==================== 辅助方法 ====================

    /**
     * 确定推理链长度。
     */
    private int determineChainLength(String intent) {
        if (intent == null || intent.isBlank()) {
            return 3;
        }
        // 计数推理关键词，每个匹配增加链长度
        long matchCount = REASONING_PATTERN.matcher(intent).results().count();
        return Math.min(DEFAULT_MAX_CHAIN_LENGTH, Math.max(3, (int) matchCount + 2));
    }

    /**
     * 确定第 i 步的步骤类型。
     *
     * <p>映射逻辑：首步 THINK，末步 OBSERVE，中间交替 THINK/ACT。</p>
     */
    private CoTStep determineStep(String intent, int index, int total) {
        if (index == 0) {
            return CoTStep.THINK;
        }
        if (index == total - 1) {
            return CoTStep.OBSERVE;
        }
        // 中间步骤交替
        return (index % 2 == 0) ? CoTStep.THINK : CoTStep.ACT;
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
        return "reason about the task";
    }

    /**
     * CoT 步骤枚举 —— 定义推理链中的步骤类型。
     */
    private enum CoTStep {
        THINK {
            @Override
            String buildDescription(String intent, int stepNum, int total) {
                return String.format("[Chain %d/%d] Think: Understand and reason about — %s",
                        stepNum, total, intent);
            }

            @Override
            boolean requiresTool() {
                return false;
            }
        },
        ACT {
            @Override
            String buildDescription(String intent, int stepNum, int total) {
                return String.format("[Chain %d/%d] Act: Execute action for — %s",
                        stepNum, total, intent);
            }

            @Override
            boolean requiresTool() {
                return true;
            }
        },
        OBSERVE {
            @Override
            String buildDescription(String intent, int stepNum, int total) {
                return String.format("[Chain %d/%d] Observe: Verify and conclude — %s",
                        stepNum, total, intent);
            }

            @Override
            boolean requiresTool() {
                return false;
            }
        };

        /** 构建步骤描述文本。 */
        abstract String buildDescription(String intent, int stepNum, int total);

        /** 该步骤是否需要工具调用。 */
        abstract boolean requiresTool();
    }
}
