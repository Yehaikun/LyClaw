package lyjew.com.lyclaw.orchestration;

import lyjew.com.lyclaw.context.ChatContext;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * 编排器接口，定义多智能体协作编排的核心执行契约。
 *
 * <p>编排器是整个框架的总指挥官，负责接收对话上下文、协调多个 Agent 的并行或串行执行、
 * 聚合结果并通过 SSE 流式返回给客户端。实现类需要处理任务调度、协作通信、
 * 进度追踪以及协作取消等关键能力。
 */
public interface Orchestrator {

    /**
     * 执行编排并将结果以 SSE 事件流的形式返回。
     *
     * @param context 当前对话上下文
     * @return SSE 事件流，每个事件包含编排输出的增量数据
     */
    Flux<ServerSentEvent<String>> execute(ChatContext context);

    /**
     * 执行编排上下文中的所有智能体任务，返回 Agent 事件流。
     *
     * @param context 编排上下文，包含任务列表及协作模式
     * @return Agent 事件流，用于追踪每个智能体的执行状态
     */
    Flux<AgentEvent> executeAgentTask(OrchestrationContext context);

    /**
     * 取消指定协作 ID 对应的编排任务。
     *
     * @param collaborationId 协作标识
     * @return 取消成功返回 true，否则返回 false
     */
    boolean cancel(String collaborationId);

    /**
     * 获取指定协作 ID 的编排执行进度。
     *
     * @param collaborationId 协作标识
     * @return 编排进度，取值范围 0.0 ~ 1.0
     */
    double getProgress(String collaborationId);
}
