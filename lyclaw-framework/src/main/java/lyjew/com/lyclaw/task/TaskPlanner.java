package lyjew.com.lyclaw.task;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.AgentResult;

public interface TaskPlanner {

    TaskPlan plan(ChatContext context);
    TaskPlan plan(ChatContext context, String userIntent);
    TaskPlan revise(TaskPlan original, ReflectionFeedback feedback);
    TaskPlan optimize(AgentResult previousResult);
    PlanGraph decompose(TaskNode rootTask, DecompositionStrategy strategy);
}
