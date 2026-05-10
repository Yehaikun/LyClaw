package lyjew.com.lyclaw.task;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.AgentResult;

/**
 * 任务规划器接口 —— 将复杂任务拆解为可执行的子任务节点，
 * 生成有向无环图（TaskPlan）。
 *
 * @since 1.0
 * @author LyClaw Team
 * @see TaskPlan
 * @see TaskNode
 */
public interface TaskPlanner {

    TaskPlan plan(ChatContext context);

    TaskPlan plan(ChatContext context, String userIntent);

    TaskPlan revise(TaskPlan original, ReflectionFeedback feedback);

    TaskPlan optimize(AgentResult previousResult);

    PlanGraph decompose(TaskNode rootTask, DecompositionStrategy strategy);
}