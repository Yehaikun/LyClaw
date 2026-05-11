package lyjew.com.lyclaw.framework.constant;

/**
 * 工具错误处理策略枚举，定义工具调用失败时的应对行为。
 */
public enum ToolErrorAction {
    /** 重试，尝试重新调用工具 */
    RETRY,
    /** 跳过，忽略该错误并继续后续流程 */
    SKIP,
    /** 终止，立即停止当前任务的执行 */
    ABORT,
    /** 回退，使用备用方案或降级逻辑处理 */
    FALLBACK
}
