package lyjew.com.lyclaw.task;

import java.util.List;

/**
 * 任务计划接口 —— 包含所有任务节点（TaskNode），
 * 提供节点依赖关系和就绪状态判断。
 *
 * @since 1.0
 * @author LyClaw Team
 * @see TaskNode
 */
public interface TaskPlan {

    List<TaskNode> getNodes();

    List<String> getDependencies(String nodeId);

    long getEstimatedCompletionTime();

    boolean isReady();
}