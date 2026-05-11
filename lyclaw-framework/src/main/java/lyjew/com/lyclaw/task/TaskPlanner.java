package lyjew.com.lyclaw.task;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.AgentResult;

/**
 * 任务规划器接口，是任务规划系统的核心入口。
 * 提供计划生成、修订、优化和分解等多种规划能力。
 */
public interface TaskPlanner {

    /**
     * 根据聊天上下文自动生成任务计划。
     *
     * @param context 聊天上下文
     * @return 生成的任务计划
     */
    TaskPlan plan(ChatContext context);

    /**
     * 根据聊天上下文和指定的用户意图生成任务计划。
     *
     * @param context    聊天上下文
     * @param userIntent 用户意图描述
     * @return 生成的任务计划
     */
    TaskPlan plan(ChatContext context, String userIntent);

    /**
     * 根据反思反馈修订现有任务计划。
     *
     * @param original 原始任务计划
     * @param feedback 反思反馈信息
     * @return 修订后的任务计划
     */
    TaskPlan revise(TaskPlan original, ReflectionFeedback feedback);

    /**
     * 基于之前执行的结果优化任务计划。
     *
     * @param previousResult 上一次执行的结果
     * @return 优化后的任务计划
     */
    TaskPlan optimize(AgentResult previousResult);

    /**
     * 使用指定策略将根任务分解为有向图结构。
     *
     * @param rootTask 根任务节点
     * @param strategy 分解策略
     * @return 分解后的计划图
     */
    PlanGraph decompose(TaskNode rootTask, DecompositionStrategy strategy);
}
