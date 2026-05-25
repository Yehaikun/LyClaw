package lyjew.com.lyclaw.agent;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.AgentResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 代理协调器接口，是 LyClaw 多代理系统的核心调度中枢。
 *
 * AgentCoordinator 负责接收外部任务请求，将任务分派给合适的代理执行，
 * 并跟踪各代理的运行状态。它是连接上层业务（聊天上下文）与底层代理池
 * 的桥梁。协调器不直接执行任务，而是根据代理注册信息、能力匹配和当前
 * 负载情况做出调度决策。此外，它还承担向所有代理广播系统级事件的功能，
 * 确保分布式的代理节点能够感知全局状态变化。
 */
public interface AgentCoordinator {

    /**
     * 将任务分派给合适的代理并异步执行，返回结果的 Future。
     *
     * @param context 聊天上下文，包含会话历史及用户输入等运行时信息
     * @param task    待执行的代理任务，包含任务类型、目标和负载数据
     * @return 异步完成的结果，包含任务执行成功或失败的信息
     */
    CompletableFuture<AgentResult> dispatch(ChatContext context, AgentTask task);

    /**
     * 取消指定代理正在执行的任务。
     *
     * @param agentId 要取消任务的代理唯一标识
     * @return 取消成功返回 true，否则返回 false
     */
    boolean cancel(String agentId);

    /**
     * 查询指定代理的当前运行状态。
     *
     * @param agentId 代理唯一标识
     * @return 代理的当前状态（如 IDLE、RUNNING、COMPLETED 等）
     */
    AgentState getState(String agentId);

    /**
     * 获取指定代理持有的所有通信通道。
     *
     * @param agentId 代理唯一标识
     * @return 该代理的所有通道列表，用于与其它代理通信
     */
    List<AgentChannel> getChannels(String agentId);

}
