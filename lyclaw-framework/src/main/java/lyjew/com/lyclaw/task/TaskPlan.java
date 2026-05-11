package lyjew.com.lyclaw.task;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * 任务计划接口，定义任务计划的通用契约。
 * JSON反序列化时默认使用 SimpleTaskPlan 作为具体实现类。
 */
@JsonDeserialize(as = SimpleTaskPlan.class)
public interface TaskPlan {

    /**
     * 获取计划中所有的任务节点列表。
     *
     * @return 任务节点列表
     */
    List<TaskNode> getNodes();

    /**
     * 获取指定节点的依赖节点ID列表。
     *
     * @param nodeId 节点ID
     * @return 依赖节点ID列表
     */
    List<String> getDependencies(String nodeId);

    /**
     * 估算完成整个计划所需的预计时间（毫秒）。
     *
     * @return 预估时间（毫秒）
     */
    long getEstimatedCompletionTime();

    /**
     * 判断当前计划是否已就绪，可开始执行。
     *
     * @return 就绪返回 true
     */
    boolean isReady();
}
