package lyjew.com.lyclaw.react;

/**
 * Agent 钩子 SPI，聚合所有生命周期扩展点，保持向后兼容。
 *
 * <p>使用者可实现此接口获得全部扩展点，或仅实现子接口
 * ({@link ModelLifecycleHook}, {@link ToolLifecycleHook}, {@link SessionLifecycleHook},
 * {@link AgentLifecycleHook}, {@link SubagentLifecycleHook},
 * {@link MessageLifecycleHook}, {@link CompactionLifecycleHook})
 * 获得更专注的扩展能力。</p>
 *
 * <h3>执行顺序</h3>
 * <ol>
 *   <li>{@link AgentLifecycleHook#beforeRequest(AgentContext)} — 请求前（按 order 升序）</li>
 *   <li>Stage 管线执行</li>
 *   <li>ReAct 循环内：
 *     <ul>
 *       <li>{@link ModelLifecycleHook#beforeModel(List, AgentContext)} — 每次 LLM 调用前</li>
 *       <li>{@link ModelLifecycleHook#afterModel(String, AgentContext)} — 每次 LLM 响应后</li>
 *       <li>{@link ToolLifecycleHook#wrapToolCall(ToolCall, AgentContext)} — 每次工具调用包装</li>
 *     </ul>
 *   </li>
 *   <li>{@link ToolLifecycleHook#wrapToolExecutor(ToolExecutor, AgentContext)} — 工具执行器装饰链</li>
 *   <li>{@link AgentLifecycleHook#afterResult(String, AgentContext)} — 结果返回前（按 order 降序）</li>
 * </ol>
 */
public interface AgentHook extends ModelLifecycleHook, ToolLifecycleHook,
        SessionLifecycleHook, AgentLifecycleHook, SubagentLifecycleHook,
        MessageLifecycleHook, CompactionLifecycleHook {

    /** 优先级，数值越小越先执行。默认 100。 */
    default int getOrder() { return 100; }

    /** 心跳提示词贡献 —— 返回要追加到心跳 prompt 的字符串。 */
    default String heartbeatPromptContribution(AgentContext ctx) { return ""; }
}
