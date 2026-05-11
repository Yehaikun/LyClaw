package lyjew.com.lyclaw.tool;

/**
 * 旧版工具执行结果封装类，已废弃。
 *
 * 用于封装工具调用的执行结果信息，包括成功/失败状态、返回数据、
 * 错误信息、执行耗时和令牌消耗。提供了静态工厂方法快速创建成功
 * 和失败的结果实例。
 *
 * @deprecated 请使用新的工具结果架构替代
 */
@Deprecated
public class ToolResult {

    /** 工具调用是否成功 */
    private final boolean success;
    /** 工具执行成功时返回的结果数据 */
    private final String result;
    /** 工具执行失败时的错误信息 */
    private final String error;
    /** 工具执行耗时（毫秒） */
    private final long elapsedMs;
    /** 工具调用消耗的令牌数量 */
    private final int tokenUsage;

    /**
     * 构造一个工具执行结果对象。
     *
     * @param success    是否成功
     * @param result     结果数据（成功时有效）
     * @param error      错误信息（失败时有效）
     * @param elapsedMs  执行耗时（毫秒）
     * @param tokenUsage 令牌消耗
     */
    public ToolResult(boolean success, String result, String error,
                      long elapsedMs, int tokenUsage) {
        this.success = success;
        this.result = result;
        this.error = error;
        this.elapsedMs = elapsedMs;
        this.tokenUsage = tokenUsage;
    }

    /**
     * 快速创建成功的工具执行结果。
     *
     * @param result 工具执行返回的数据
     * @return 标记为成功的 ToolResult 实例
     */
    public static ToolResult success(String result) {
        return new ToolResult(true, result, null, 0L, 0);
    }

    /**
     * 快速创建失败的工具执行结果。
     *
     * @param error 错误描述信息
     * @return 标记为失败的 ToolResult 实例
     */
    public static ToolResult failure(String error) {
        return new ToolResult(false, null, error, 0L, 0);
    }

    /** @return 工具调用是否成功 */
    public boolean isSuccess() { return success; }

    /** @return 工具执行的结果数据 */
    public String getResult() { return result; }

    /** @return 错误信息，成功时返回 null */
    public String getError() { return error; }

    /** @return 执行耗时（毫秒） */
    public long getElapsedMs() { return elapsedMs; }

    /** @return 令牌消耗数 */
    public int getTokenUsage() { return tokenUsage; }
}
