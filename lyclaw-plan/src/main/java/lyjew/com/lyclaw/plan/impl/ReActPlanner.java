package lyjew.com.lyclaw.plan.impl;

import lyjew.com.lyclaw.config.PlanProperties;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.task.DecompositionStrategy;
import lyjew.com.lyclaw.task.PlanGraph;
import lyjew.com.lyclaw.task.ReflectionFeedback;
import lyjew.com.lyclaw.task.SimpleTaskPlan;
import lyjew.com.lyclaw.task.TaskNode;
import lyjew.com.lyclaw.task.TaskPlan;
import lyjew.com.lyclaw.task.TaskPlanner;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ReAct (Reasoning + Acting) 规划器 —— 实现 Thought → Action → Observation 循环。
 *
 * <p>ReAct 模式是一种将推理和行动交织在一起的智能体范式。
 * 每次循环包含三个紧密耦合的阶段：
 * <ol>
 *   <li><b>Thought（思考）</b>：分析当前状态，决定下一步行动</li>
 *   <li><b>Action（行动）</b>：执行工具调用或操作</li>
 *   <li><b>Observation（观察）</b>：检查行动结果，更新理解</li>
 * </ol>
 * </p>
 *
 * <p>循环继续直到达到最大循环次数或任务完成条件。
 * 每个循环中，Thought 和 Action 形成依赖对，观察结果后进入下一轮思考。</p>
 *
 * <p><b>设计动机</b>：ReAct 是目前主流 AI Agent（如 LangChain、AutoGPT）的核心模式。
 * 将其形式化为 TaskPlan 使得引擎可以精确控制每轮循环、支持中断/恢复、
 * 以及将循环过程完整记录到 TaskLedger。</p>
 *
 * @since 2.0
 * @author LyClaw Team
 * @see TaskPlanner
 * @see <a href="https://arxiv.org/abs/2210.03629">ReAct: Synergizing Reasoning and Acting in Language Models</a>
 */
@Service("reActPlanner")
public class ReActPlanner implements TaskPlanner {

    /** 最大 ReAct 循环次数，由 PlanProperties 注入 */
    private int maxCycles = 5;

    @Autowired
    public void setPlanProperties(PlanProperties props) {
        this.maxCycles = props.getMaxCycles();
    }

    /** 默认超时（毫秒） */
    private static final long DEFAULT_TIMEOUT_MS = 60_000L;

    /** Action 节点的默认工具列表 */
    private static final List<String> ACTION_TOOLS = List.of("web_search", "code_executor", "file_read");

    /**
     * 构建 ReAct 任务计划。
     *
     * <p>生成 N 轮 Thought → Action → Observation 循环。
     * 每轮的 Thought 和 Action 是同一循环内的顺序对，
     * Observation 作为该轮的收尾，下一轮的 Thought 依赖上一轮的 Observation。</p>
     *
     * <p>结构：
     * <pre>
     * Thought₀ → Action₀ → Observation₀
     *   ↓
     * Thought₁ → Action₁ → Observation₁
     *   ↓
     * Thought₂ → Action₂ → Observation₂ (final)
     * </pre>
     * </p>
     *
     * @param context    对话上下文
     * @param userIntent 用户意图
     * @return ReAct 任务计划
     */
    @Override
    public TaskPlan plan(ChatContext context, String userIntent) {
        String intent = extractIntent(context, userIntent);
        int cycles = determineCycles(intent);

        List<TaskNode> nodes = new ArrayList<>();
        String prefix = "react-" + UUID.randomUUID().toString().substring(0, 8);
        String prevObsId = null;

        for (int cycle = 0; cycle < cycles; cycle++) {
            boolean isLast = (cycle == cycles - 1);

            // Thought 节点
            String thoughtId = prefix + "-t" + cycle;
            List<String> thoughtDeps = prevObsId != null ? List.of(prevObsId) : List.of();
            String thoughtDesc = isLast
                    ? String.format("[Cycle %d/%d FINAL] Thought: Synthesize final answer for — %s",
                            cycle + 1, cycles, intent)
                    : String.format("[Cycle %d/%d] Thought: Analyze state and plan next action for — %s",
                            cycle + 1, cycles, intent);
            TaskNode thought = new TaskNode(thoughtId, "THOUGHT", thoughtDesc,
                    List.of(), thoughtDeps, DEFAULT_TIMEOUT_MS);
            nodes.add(thought);

            // Action 节点（依赖对应的 Thought）
            String actionId = prefix + "-a" + cycle;
            String actionDesc = isLast
                    ? String.format("[Cycle %d/%d] Action: Execute final response for — %s",
                            cycle + 1, cycles, intent)
                    : String.format("[Cycle %d/%d] Action: Execute tool call for — %s",
                            cycle + 1, cycles, intent);
            TaskNode action = new TaskNode(actionId, "ACTION", actionDesc,
                    isLast ? List.of() : ACTION_TOOLS,
                    List.of(thoughtId), DEFAULT_TIMEOUT_MS);
            nodes.add(action);

            // Observation 节点（依赖对应的 Action）
            String obsId = prefix + "-o" + cycle;
            String obsDesc = isLast
                    ? String.format("[Cycle %d/%d] Observe: Final verification for — %s",
                            cycle + 1, cycles, intent)
                    : String.format("[Cycle %d/%d] Observe: Check action result and update understanding for — %s",
                            cycle + 1, cycles, intent);
            TaskNode obs = new TaskNode(obsId, "OBSERVATION", obsDesc,
                    List.of("validation"), List.of(actionId), DEFAULT_TIMEOUT_MS);
            nodes.add(obs);

            prevObsId = obsId;
        }

        return new SimpleTaskPlan(nodes);
    }

    /**
     * 使用上下文消息进行 ReAct 规划。
     */
    @Override
    public TaskPlan plan(ChatContext context) {
        return plan(context, null);
    }

    /**
     * 修订 ReAct 计划 —— 根据反馈调整循环次数或内容。
     *
     * <ul>
     *   <li>质量分低：增加一轮额外循环</li>
     *   <li>detectedErrors 包含 "premature"：增加循环</li>
     *   <li>feedback 建议减少循环：缩减到建议数量</li>
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
        String strategy = feedback.getSuggestedStrategy() != null
                ? feedback.getSuggestedStrategy().toLowerCase() : "";

        // 需要更多循环
        boolean needMoreCycles = feedback.getQualityScore() < 0.3
                || strategy.contains("more_cycles")
                || hasPrematureStop(feedback);

        if (needMoreCycles) {
            // 追加一轮
            List<TaskNode> newNodes = new ArrayList<>(nodes);
            String lastId = nodes.get(nodes.size() - 1).getNodeId();
            String prefix = "react-vfy-" + UUID.randomUUID().toString().substring(0, 8);

            newNodes.add(new TaskNode(prefix + "-t", "THOUGHT",
                    "Extra cycle — Re-evaluate: " + rootDesc,
                    List.of(), List.of(lastId), DEFAULT_TIMEOUT_MS));
            newNodes.add(new TaskNode(prefix + "-a", "ACTION",
                    "Extra cycle — Re-execute: " + rootDesc,
                    ACTION_TOOLS, List.of(prefix + "-t"), DEFAULT_TIMEOUT_MS));
            newNodes.add(new TaskNode(prefix + "-o", "OBSERVATION",
                    "Extra cycle — Final check: " + rootDesc,
                    List.of(), List.of(prefix + "-a"), DEFAULT_TIMEOUT_MS));

            return new SimpleTaskPlan(newNodes);
        }

        return original;
    }

    /**
     * 基于先前结果创建优化的 ReAct 计划。
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

        String prefix = "react-opt-" + UUID.randomUUID().toString().substring(0, 8);
        List<TaskNode> nodes = new ArrayList<>();

        nodes.add(new TaskNode(prefix + "-t", "THOUGHT",
                "Reflect on previous ReAct result: " + summary,
                List.of(), List.of(), DEFAULT_TIMEOUT_MS));
        nodes.add(new TaskNode(prefix + "-a", "ACTION",
                "Execute optimized action: " + summary,
                ACTION_TOOLS, List.of(prefix + "-t"), DEFAULT_TIMEOUT_MS));
        nodes.add(new TaskNode(prefix + "-o", "OBSERVATION",
                "Verify optimization: " + summary,
                List.of(), List.of(prefix + "-a"), DEFAULT_TIMEOUT_MS));

        return new SimpleTaskPlan(nodes);
    }

    /**
     * 按 ReAct 模式分解任务 —— 创建完整的 Thought→Action→Observation 子图。
     */
    @Override
    public PlanGraph decompose(TaskNode rootTask, DecompositionStrategy strategy) {
        PlanGraph graph = new PlanGraph();
        graph.addNode(rootTask);

        String desc = rootTask.getDescription();
        int cycles = determineCycles(desc);
        String prefix = rootTask.getNodeId() + "-react";
        String prevId = rootTask.getNodeId();

        for (int cycle = 0; cycle < cycles; cycle++) {
            String thoughtId = prefix + "-t" + cycle;
            TaskNode thought = new TaskNode(thoughtId, "THOUGHT",
                    "[ReAct " + (cycle + 1) + "/" + cycles + "] Think about: " + desc,
                    List.of(), List.of(prevId), rootTask.getTimeoutMs());
            graph.addNode(thought);
            graph.addEdge(prevId, thoughtId);

            String actionId = prefix + "-a" + cycle;
            TaskNode action = new TaskNode(actionId, "ACTION",
                    "[ReAct " + (cycle + 1) + "/" + cycles + "] Act on: " + desc,
                    ACTION_TOOLS, List.of(thoughtId), rootTask.getTimeoutMs());
            graph.addNode(action);
            graph.addEdge(thoughtId, actionId);

            String obsId = prefix + "-o" + cycle;
            TaskNode obs = new TaskNode(obsId, "OBSERVATION",
                    "[ReAct " + (cycle + 1) + "/" + cycles + "] Observe: " + desc,
                    List.of(), List.of(actionId), rootTask.getTimeoutMs());
            graph.addNode(obs);
            graph.addEdge(actionId, obsId);

            prevId = obsId;
        }

        return graph;
    }

    // ==================== 辅助方法 ====================

    /**
     * 根据任务描述确定需要的 ReAct 循环次数。
     *
     * <p>复杂任务需要更多循环。基于描述长度和关键词估算。</p>
     */
    private int determineCycles(String intent) {
        if (intent == null || intent.isBlank()) {
            return 2;
        }
        // 描述越长 → 越复杂 → 更多循环
        int baseCycles = Math.max(1, intent.length() / 50);
        return Math.min(maxCycles, Math.max(2, baseCycles));
    }

    private boolean hasPrematureStop(ReflectionFeedback feedback) {
        if (feedback.getDetectedErrors() == null) return false;
        return feedback.getDetectedErrors().stream()
                .anyMatch(e -> e.toLowerCase().contains("premature")
                        || e.toLowerCase().contains("early")
                        || e.toLowerCase().contains("incomplete")
                        || e.toLowerCase().contains("不完整"));
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
        return "execute task with reasoning and action";
    }
}
