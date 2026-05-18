package lyjew.com.lyclaw.react;

import lyjew.com.lyclaw.action.ActionResult;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.reflect.ReflectionEngine;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import lyjew.com.lyclaw.task.ReflectionFeedback;
import lyjew.com.lyclaw.task.TaskPlan;
import lyjew.com.lyclaw.task.TaskPlanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reflexion 自校正循环——执行 → 反思 → 修订 → 重试。
 *
 * <p>当任务执行失败或输出质量不达标时，通过 ReflectionEngine 诊断问题，
 * 由 TaskPlanner 修订计划，然后重新执行。循环直到质量达标或达到最大重试次数。
 *
 * <pre>
 * ReflexionLoop loop = new ReflexionLoop(engine, planner, maxRetries, qualityThreshold);
 * ReflexionResult result = loop.execute(plan, context, executor);
 * </pre>
 */
public class ReflexionLoop {

    private static final Logger log = LoggerFactory.getLogger(ReflexionLoop.class);

    private final ReflectionEngine reflectionEngine;
    private final TaskPlanner taskPlanner;
    private final int maxRetries;
    private final double qualityThreshold;

    public ReflexionLoop(ReflectionEngine reflectionEngine, TaskPlanner taskPlanner,
                         int maxRetries, double qualityThreshold) {
        this.reflectionEngine = reflectionEngine;
        this.taskPlanner = taskPlanner;
        this.maxRetries = Math.max(0, maxRetries);
        this.qualityThreshold = qualityThreshold;
    }

    /**
     * 单步骤执行器——执行一个任务节点并返回 ActionResult。
     */
    @FunctionalInterface
    public interface StepExecutor {
        ActionResult execute(String nodeId, String description) throws Exception;
    }

    /**
     * 执行 Reflexion 循环。
     *
     * @param plan     初始任务计划
     * @param context  聊天上下文
     * @param executor 步骤执行器
     * @return 循环结果
     */
    public ReflexionResult execute(TaskPlan plan, ChatContext context, StepExecutor executor) {
        List<ReflexionResult.Attempt> attempts = new ArrayList<>();
        TaskPlan currentPlan = plan;
        String loopId = UUID.randomUUID().toString().substring(0, 8);
        long startTime = System.currentTimeMillis();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            log.info("[Reflexion {}] 尝试 {}/{}", loopId, attempt + 1, maxRetries + 1);

            ActionResult result = null;
            Exception executionError = null;

            try {
                if (currentPlan.getNodes() != null) {
                    for (var node : currentPlan.getNodes()) {
                        result = executor.execute(node.getNodeId(), node.getDescription());
                        if (!result.isSuccess()) break;
                    }
                }
            } catch (Exception e) {
                executionError = e;
                log.warn("[Reflexion {}] 执行异常: {}", loopId, e.getMessage());
            }

            // 构建反思反馈
            ReflectionFeedback feedback = null;
            double qualityScore = 1.0;

            if (reflectionEngine != null && result != null) {
                try {
                    ReflectionReport report = reflectionEngine.reflect(context, result);
                    qualityScore = report.getOverallScore();

                    feedback = ReflectionFeedback.builder()
                            .reportId(report.getReflectionId())
                            .qualityScore(qualityScore)
                            .detectedErrors(report.getErrors() != null
                                    ? report.getErrors().stream().map(Object::toString).toList()
                                    : List.of())
                            .suggestedStrategy(report.getSuggestion() != null
                                    && report.getSuggestion().getType() != null
                                    ? report.getSuggestion().getType().name() : null)
                            .build();
                } catch (Exception e) {
                    log.warn("[Reflexion {}] 反思评估失败: {}", loopId, e.getMessage());
                }
            }

            if (executionError != null) {
                feedback = ReflectionFeedback.builder()
                        .qualityScore(0.0)
                        .detectedErrors(List.of(executionError.getMessage()))
                        .suggestedStrategy("replan")
                        .adjustedPrompt(currentPlan.getNodes() != null && !currentPlan.getNodes().isEmpty()
                                ? currentPlan.getNodes().get(0).getDescription() : null)
                        .build();
                qualityScore = 0.0;
            }

            attempts.add(new ReflexionResult.Attempt(attempt, result, qualityScore, feedback));

            // 质量达标或已是最后一次尝试
            if (qualityScore >= qualityThreshold || attempt >= maxRetries) {
                break;
            }

            // 修订计划
            if (taskPlanner != null && feedback != null) {
                TaskPlan revised = taskPlanner.revise(currentPlan, feedback);
                if (revised != null && revised != currentPlan) {
                    currentPlan = revised;
                    log.info("[Reflexion {}] 计划已修订，新的节点数: {}", loopId,
                            revised.getNodes() != null ? revised.getNodes().size() : 0);
                }
            }
        }

        long totalMs = System.currentTimeMillis() - startTime;
        return new ReflexionResult(loopId, attempts, totalMs);
    }
}
