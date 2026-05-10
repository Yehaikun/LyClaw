package lyjew.com.lyclaw.tool;

/**
 * 工具错误决策枚举 —— 工具执行失败后，ToolCallPolicy 决定接下来怎么做。
 *
 * <p>当工具调用抛出异常或返回错误时，ToolCallPolicy.handleToolError()
 * 根据当前上下文和错误类型返回一个 ToolErrorAction，
 * 引导 ToolCallLoop 执行相应的后续操作。</p>
 *
 * <p><b>设计动机</b>：只有四种明确的决策路径，用枚举固化决策空间，
 * 避免使用 int 常量或 String 魔数来传递错误处理策略。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>ToolCallPolicy.handleToolError() 的返回值</li>
 *   <li>ToolCallLoop 根据返回值决定循环行为</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public enum ToolErrorAction {

    /**
     * 重试当前工具调用。
     * ToolCallLoop 会重新发起相同参数的 execute() 调用。
     * 每次重试前等待时间按指数退避：1s、2s、4s...
     */
    RETRY,

    /**
     * 跳过当前工具。
     * 把错误信息作为 tool_result 注入到对话上下文中，
     * 让模型知道这个工具调用失败了并自行处理。
     */
    SKIP,

    /**
     * 终止整个工具调用循环。
     * 中断 while 循环，直接返回错误给用户，不再调用模型。
     */
    ABORT,

    /**
     * 使用备用工具/方案。
     * ToolCallPolicy 可以配置一个 fallback 工具名，
     * 循环自动切换到备用工具继续执行。
     */
    FALLBACK
}