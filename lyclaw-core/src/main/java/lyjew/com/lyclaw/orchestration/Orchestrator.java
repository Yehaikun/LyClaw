package lyjew.com.lyclaw.orchestration;

import lyjew.com.lyclaw.context.ChatContext;
import reactor.core.publisher.Flux;

/**
 * 编排器 —— 四元循环调度 + 多Agent协作的顶层入口。
 *
 * <p>负责调度四元模块 (Plan → Memory → Action → Reflect) 的循环执行,
 * 管理多 Agent 生命周期, 并协调 Agent 间的通信与协作。</p>
 *
 * @since 2.0
 */
public interface Orchestrator {

    /**
     * 执行一次完整的四元循环。
     *
     * @param context 对话上下文
     * @return 流式输出
     */
    Flux<String> execute(ChatContext context);

    /**
     * 执行 Agent 任务。
     *
     * @param context 编排上下文
     * @return Agent 事件流
     */
    Flux<AgentEvent> executeAgentTask(OrchestrationContext context);

    /** 取消指定协作 */
    boolean cancel(String collaborationId);

    /** 获取协作进度 */
    double getProgress(String collaborationId);
}
