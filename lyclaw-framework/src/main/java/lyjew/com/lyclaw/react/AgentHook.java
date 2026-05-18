package lyjew.com.lyclaw.react;

/**
 * Agent 调用钩子 SPI，在代理 Agent 的请求生命周期中提供扩展点。
 *
 * <h3>钩子执行顺序</h3>
 * <ol>
 *   <li>{@link #beforeRequest(AgentContext)} — 按 order 升序</li>
 *   <li>{@link #wrapToolExecutor(ToolExecutor, AgentContext)} — 按 order 升序（装饰链）</li>
 *   <li>ReAct 引擎执行</li>
 *   <li>{@link #afterResult(String, AgentContext)} — 按 order 降序</li>
 * </ol>
 *
 * <p>每个安全/增强能力实现为一个独立的 AgentHook，通过 order 控制先后顺序。
 * 不需要修改 AgentInvocationHandler 即可增加新能力。
 */
public interface AgentHook {

    /**
     * 请求发送前回调。可用于安全审核、内容过滤、计划提示注入等。
     * 抛出异常可中断请求。
     */
    default void beforeRequest(AgentContext ctx) {}

    /**
     * 包装 ToolExecutor，形成装饰链。用于沙箱隔离、工具审批等。
     *
     * @param inner 当前链中的 ToolExecutor（可能已被前面的 hook 包装过）
     * @param ctx   代理调用上下文
     * @return 包装后的 ToolExecutor
     */
    default ToolExecutor wrapToolExecutor(ToolExecutor inner, AgentContext ctx) {
        return inner;
    }

    /**
     * 结果返回前回调。可用于轻量校验、结果脱敏、日志记录等。
     *
     * @param result ReAct 引擎返回的最终文本
     * @param ctx    代理调用上下文
     * @return 处理后的结果（可以修改）
     */
    default String afterResult(String result, AgentContext ctx) {
        return result;
    }

    /** 优先级，数值越小越先执行。默认 100（在系统 hook 之后）。 */
    default int getOrder() { return 100; }
}
