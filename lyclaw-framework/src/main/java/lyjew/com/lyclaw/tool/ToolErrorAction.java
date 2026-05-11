package lyjew.com.lyclaw.tool;

/**
 * 工具错误处理动作枚举。
 *
 * 定义了工具调用发生错误时的四种处理策略：
 * <ul>
 *   <li>{@link #RETRY} - 重试当前工具调用</li>
 *   <li>{@link #SKIP} - 跳过当前工具调用，继续执行后续工具</li>
 *   <li>{@link #ABORT} - 中止全部工具调用流程</li>
 *   <li>{@link #FALLBACK} - 使用备用方案处理当前调用</li>
 * </ul>
 * 该枚举被 {@link ToolCallPolicy#handleToolError} 方法使用。
 */
public enum ToolErrorAction {
    /** 重试当前工具调用 */
    RETRY,
    /** 跳过当前工具，继续下一个 */
    SKIP,
    /** 中止整个工具调用流程 */
    ABORT,
    /** 使用降级或备用方案 */
    FALLBACK
}
