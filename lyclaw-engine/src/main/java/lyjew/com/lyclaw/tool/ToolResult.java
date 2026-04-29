package lyjew.com.lyclaw.tool;

/**
 * 工具执行结果 —— 工具执行完毕后的返回值。
 *
 * <p>每个 Tool.execute() 调用都会返回一个 ToolResult 对象，
 * 包含执行是否成功、结果数据、错误信息、耗时和 Token 消耗。</p>
 *
 * <p><b>设计动机</b>：统一所有工具（内置工具 / MCP 工具）的执行结果格式。
 * 上层调用方（ToolCallLoop / Pipeline）只需要处理这一种结果类型，
 * 不需要针对不同工具做不同的结果解析。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>Tool.execute() 的返回值</li>
 *   <li>ToolCallLoop 将 ToolResult 注入到 ChatContext 中</li>
 *   <li>ErrorPolicy.onToolError() 判断是否重试</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class ToolResult {

    /** 是否执行成功。true 表示工具正常执行并返回结果 */
    private final boolean success;

    /**
     * 执行结果 —— 以 JSON 字符串格式存储。
     * 例如 web_search 工具返回：{"results": [{"title": "...", "url": "..."}]}
     */
    private final String result;

    /** 错误信息 —— 执行失败时的错误描述，成功时为 null */
    private final String error;

    /** 执行耗时（毫秒） */
    private final long elapsedMs;

    /** 工具执行消耗的 Token（如果工具调用涉及模型调用）；不消耗 Token 的工具为 0 */
    private final int tokenUsage;

    /**
     * 构造一个 ToolResult 实例。
     *
     * @param success    是否成功
     * @param result     执行结果（JSON 格式）
     * @param error      错误信息
     * @param elapsedMs  执行耗时（毫秒）
     * @param tokenUsage Token 消耗
     */
    public ToolResult(boolean success, String result, String error,
                      long elapsedMs, int tokenUsage) {
        this.success = success;
        this.result = result;
        this.error = error;
        this.elapsedMs = elapsedMs;
        this.tokenUsage = tokenUsage;
    }
    public static ToolResult success(String result) {
        return new ToolResult(true, result, null, 0L, 0);
    }

    public static ToolResult failure(String error) {
        return new ToolResult(false, null, error, 0L, 0);
    }
    /** @return 是否执行成功 */
    public boolean isSuccess() { return success; }

    /** @return 执行结果（JSON 字符串格式） */
    public String getResult() { return result; }

    /** @return 错误信息 */
    public String getError() { return error; }

    /** @return 执行耗时（毫秒） */
    public long getElapsedMs() { return elapsedMs; }

    /** @return Token 消耗 */
    public int getTokenUsage() { return tokenUsage; }
}