package lyjew.com.lyclaw.react;

import java.util.Set;
import java.util.function.Consumer;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.mesh.AgentExecutionEvent;

import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * ReAct（Reasoning-Acting）引擎接口，定义 LLM 多轮推理-行动循环的统一入口。
 *
 * <p>ReAct 是 LyClaw 框架中最核心的 LLM 交互原语。无论是 DAG 计划生成时的
 * 任务分解（LLMTaskPlanner），还是 DAG 节点的独立执行（RespondStage /
 * executeDAGNode），底层都通过 ReAct 循环与 LLM 交互：
 * <ol>
 *   <li>发送提示词 + 工具定义给 LLM</li>
 *   <li>LLM 返回文本回复 或 工具调用请求</li>
 *   <li>如果是工具调用 → 执行工具 → 将结果反馈给 LLM → 回到步骤 2</li>
 *   <li>如果是文本回复 → 结束循环，返回结果</li>
 * </ol>
 *
 * <p>实现类通过 {@link InteractionMode} 注解声明模式名称，框架可根据名称
 * 选择不同的交互模式（ReAct / CoT / Tree-of-Thought）。
 *
 * @see InteractionMode
 * @see ToolExecutor
 */
public interface ReActEngine {

    /**
     * 非流式 ReAct 循环（带事件回调）。
     * 直接对 messages 列表进行多轮追加（assistant + tool 消息），
     * 直到 LLM 返回纯文本回复或达到最大轮数。
     *
     * @param chatFacade   聊天门面，用于 LLM 调用
     * @param request      聊天请求（其 messages 列表会被原地修改）
     * @param toolExecutor 工具执行器，无工具时传 null
     * @param eventCallback 执行事件回调（每轮推理、工具调用时触发），可为 null
     * @return 最终的 LLM 文本回复
     */
    String execute(ChatFacade chatFacade, ChatRequest request, ToolExecutor toolExecutor,
                   Consumer<AgentExecutionEvent> eventCallback);

    /**
     * 非流式 ReAct 循环（无事件回调，向后兼容）。
     */
    default String execute(ChatFacade chatFacade, ChatRequest request, ToolExecutor toolExecutor) {
        return execute(chatFacade, request, toolExecutor, null);
    }

    /**
     * 流式 ReAct，先尝试 stream=true 探测是否有工具调用。
     *
     * <p>工作流程：
     * <ol>
     *   <li>以 stream=true 调用 LLM，实时分析每个 chunk</li>
     *   <li>若检测到纯文本内容 → 直接透传 SSE 事件给前端（真流式）</li>
     *   <li>若检测到 tool_calls → 收集碎片合并后重启非流式 ReAct 循环，
     *       循环结束后将结果拆分为 SSE 事件模拟流式输出</li>
     * </ol>
     *
     * @param chatFacade   聊天门面
     * @param request      聊天请求
     * @param toolExecutor 工具执行器
     * @return SSE 事件流
     */
    Flux<ServerSentEvent<String>> executeStream(ChatFacade chatFacade, ChatRequest request,
                                                ToolExecutor toolExecutor);

    /**
     * 设置需要用户审批的工具名集合（通常是非只读工具）。
     * 引擎在执行这些工具前会通过 SSE 推送 tool_approval 事件并等待用户确认。
     *
     * @param toolNames 需要审批的工具名集合
     */
    default void setApprovalRequired(Set<String> toolNames) {
        // 默认空实现，子类可按需覆写
    }
}
