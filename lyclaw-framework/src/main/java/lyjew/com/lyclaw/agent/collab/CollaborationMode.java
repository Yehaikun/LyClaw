package lyjew.com.lyclaw.agent.collab;

import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.orchestration.OrchestrationContext;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 协作模式接口，定义多代理协作的编排策略和执行流程。
 *
 * CollaborationMode 是 LyClaw 协作框架的策略抽象，每种实现代表一种
 * 多代理协作范式（如顺序编排、并行投票、辩论反思）。它负责根据当前
 * 可用的代理池和编排上下文生成分配计划（assign），然后驱动协作执行
 * （execute）。每种模式还需要声明其拓扑偏好，以便在自动模式选择时
 * 与任务结构匹配。此外，模式通过 supportsDynamicScaling 来声明是否
 * 支持在执行过程中动态增减代理节点。
 */
public interface CollaborationMode {

    /**
     * 获取该模式的唯一标识符。
     *
     * @return 模式 ID，如 "debate"、"voting"、"sequential"
     */
    String getModeId();

    /**
     * 获取该模式下首选的网络拓扑类型。
     *
     * @return 拓扑类型枚举值，如 STAR、MESH
     */
    TopologyType getPreferredTopology();

    /**
     * 根据可用代理和编排上下文，生成任务分配计划。
     *
     * @param availableAgents 当前可用的代理列表
     * @param ctx              编排上下文，包含任务结构信息
     * @return 生成的分配计划
     */
    AssignmentPlan assign(List<AgentHandle> availableAgents, OrchestrationContext ctx);

    /**
     * 执行协作计划，驱动各代理按分配计划完成协作任务。
     *
     * @param ctx 协作上下文，包含参与者、共享状态和超时配置
     * @return 异步返回协作的整体执行结果
     */
    CompletableFuture<AgentResult> execute(CollaborationContext ctx);

    /**
     * 取消指定协作会话的执行。
     *
     * @param collaborationId 协作会话的唯一标识
     * @return 取消成功返回 true，否则返回 false
     */
    boolean cancel(String collaborationId);

    /**
     * 获取协作执行的进度。
     *
     * @param collaborationId 协作会话的唯一标识
     * @return 进度值（0.0 ~ 1.0）
     */
    double getProgress(String collaborationId);

    /**
     * 查询该模式是否支持动态扩缩容。
     *
     * @return 支持返回 true，否则返回 false
     */
    boolean supportsDynamicScaling();
}
