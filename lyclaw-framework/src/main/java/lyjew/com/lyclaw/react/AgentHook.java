package lyjew.com.lyclaw.react;

import java.util.List;

import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ToolCall;

/**
 * Agent 调用钩子 SPI，提供请求级和步级扩展点。
 *
 * <h3>执行顺序</h3>
 * <ol>
 *   <li>{@link #beforeRequest(AgentContext)} — 请求前（按 order 升序）</li>
 *   <li>Stage 管线执行</li>
 *   <li>ReAct 循环内：
 *     <ul>
 *       <li>{@link #beforeModel(List, AgentContext)} — 每次 LLM 调用前</li>
 *       <li>{@link #afterModel(String, AgentContext)} — 每次 LLM 响应后</li>
 *       <li>{@link #wrapToolCall(ToolCall, AgentContext)} — 每次工具调用包装</li>
 *     </ul>
 *   </li>
 *   <li>{@link #wrapToolExecutor(ToolExecutor, AgentContext)} — 工具执行器装饰链</li>
 *   <li>{@link #afterResult(String, AgentContext)} — 结果返回前（按 order 降序）</li>
 * </ol>
 */
public interface AgentHook {

    /** 请求发送前回调。抛出异常可中断请求。 */
    default void beforeRequest(AgentContext ctx) {}

    /**
     * LLM 调用前回调，可注入计划上下文、动态调整消息列表。
     * @param messages 当前消息列表（不可变视图）
     * @param ctx Agent 上下文
     * @return 修改后的消息列表，返回原列表表示不修改
     */
    default List<Message> beforeModel(List<Message> messages, AgentContext ctx) {
        return messages;
    }

    /**
     * LLM 响应后回调，可检测有害内容、记录日志。
     * @param response LLM 原始响应文本
     * @param ctx Agent 上下文
     * @return 处理后的响应文本
     */
    default String afterModel(String response, AgentContext ctx) {
        return response;
    }

    /** 包装单次工具调用，比 wrapToolExecutor 更细粒度。 */
    default ToolCall wrapToolCall(ToolCall toolCall, AgentContext ctx) {
        return toolCall;
    }

    /**
     * 包装 ToolExecutor，形成装饰链。
     * @param inner 当前链中的 ToolExecutor
     * @param ctx   Agent 上下文
     * @return 包装后的 ToolExecutor
     */
    default ToolExecutor wrapToolExecutor(ToolExecutor inner, AgentContext ctx) {
        return inner;
    }

    /**
     * 结果返回前回调。
     * @param result ReAct 引擎返回的最终文本
     * @param ctx    Agent 上下文
     * @return 处理后的结果
     */
    default String afterResult(String result, AgentContext ctx) {
        return result;
    }

    /** 优先级，数值越小越先执行。默认 100。 */
    default int getOrder() { return 100; }
}
