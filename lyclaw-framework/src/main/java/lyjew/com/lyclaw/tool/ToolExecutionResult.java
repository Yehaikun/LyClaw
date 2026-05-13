package lyjew.com.lyclaw.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具执行结果封装类，是框架中唯一的工具调用结果类型，用于标准化工具执行的成功和失败信息。
 *
 * <p>本类是 LyClaw 框架工具调用体系中的核心数据载体，封装了一次工具执行操作的全部结果
 * 信息。作为不可变对象，所有字段通过全参数构造器注入，构造器会自动将 null 值转换为安全
 * 的默认值（字符串转为空字符串、Map 转为空 HashMap），确保对象在任何情况下都处于一致
 * 的状态，避免下游代码出现 NullPointerException。框架中所有工具（无论是内置工具还是
 * 用户自定义工具）的执行结果都应包装为 ToolExecutionResult 实例后返回，以保证工具调用
 * 链路的类型统一性和可追踪性。
 *
 * <p>核心字段说明：
 * <ul>
 *   <li><b>success</b>（boolean）：执行是否成功，是判断结果状态的主要依据。框架在收集
 *       工具结果后会根据此字段决定是否将结果注入到上下文中供 AI 模型参考</li>
 *   <li><b>result</b>（String）：成功时的输出内容，通常为工具执行产生的结果文本。
 *       失败时此字段为空字符串</li>
 *   <li><b>error</b>（String）：失败时的错误描述信息，应包含足够的人类可读信息以
 *       便于排查问题。成功时此字段为空字符串</li>
 *   <li><b>elapsedMs</b>（long）：工具执行耗时（毫秒），用于性能监控和慢查询分析</li>
 *   <li><b>tokenUsage</b>（int）：工具执行消耗的 Token 数量，用于成本统计和配额管理</li>
 *   <li><b>toolName</b>（String）：产生此结果的工具名称，用于多工具环境下的结果追踪</li>
 *   <li><b>metadata</b>（Map&lt;String, Object&gt;）：可扩展的附加元数据，用于在
 *       不同组件间传递自定义信息</li>
 * </ul>
 *
 * <p>实例创建方式：推荐通过静态工厂方法（{@link #success(String)} /
 * {@link #failure(String)} 及其重载版本）或 Lombok Builder 快速创建实例。静态工厂方法
 * 简化了常见场景（仅需标记成功/失败并携带结果/错误信息）的实例构建，而 Builder 方式
 * 适用于需要精确控制所有字段（如 elapsedMs、tokenUsage、metadata 等）的复杂场景。
 *
 * @see lyjew.com.lyclaw.tool.ToolRegistry
 */
@Getter
public class ToolExecutionResult {

    /** 执行是否成功 */
    private final boolean success;
    /** 成功时的输出内容 */
    private final String result;
    /** 失败时的错误信息 */
    private final String error;
    /** 执行耗时（毫秒） */
    private final long elapsedMs;
    /** 消耗的 Token 数 */
    private final int tokenUsage;
    /** 工具名称 */
    private final String toolName;
    /** 附加元数据 */
    private final Map<String, Object> metadata;

    /**
     * 全参数构造器，null 值自动转为空字符串/空 Map 以保证不可变性。
     */
    @Builder
    public ToolExecutionResult(boolean success, String result, String error,
                      long elapsedMs, int tokenUsage, String toolName,
                      Map<String, Object> metadata) {
        this.success = success;
        this.result = result != null ? result : "";
        this.error = error != null ? error : "";
        this.elapsedMs = elapsedMs;
        this.tokenUsage = tokenUsage;
        this.toolName = toolName != null ? toolName : "";
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
    }

    /**
     * 静态工厂方法：快速构建工具执行成功的简单结果对象。
     *
     * <p>创建的实例 success 为 true，仅设置 result 字段，其余字段均为默认值
     * （error 为空串，elapsedMs=0，tokenUsage=0，toolName 为空串，metadata 为空 Map）。
     * 适用于快速构建不需要详细执行信息的简单成功结果。
     *
     * @param result 工具执行的输出内容字符串
     * @return 标记为成功的 ToolExecutionResult 实例，仅包含执行结果
     */
    public static ToolExecutionResult success(String result) {
        return new ToolExecutionResult(true, result, "", 0L, 0, "", null);
    }

    /**
     * 静态工厂方法：构建工具执行成功的结果对象，并附带工具名称标识。
     *
     * <p>与单参数版本相比，增加了 toolName 字段用于标识是哪个工具产生的成功结果。
     * 这对于日志记录、结果追踪和多工具协作场景非常有用。其余字段（elapsedMs、
     * tokenUsage、metadata）均为默认值。
     *
     * @param result   工具执行的输出内容字符串
     * @param toolName 产生此结果的工具名称，用于结果追踪和日志关联
     * @return 标记为成功并携带工具名的 ToolExecutionResult 实例
     */
    public static ToolExecutionResult success(String result, String toolName) {
        return new ToolExecutionResult(true, result, "", 0L, 0, toolName, null);
    }

    /**
     * 静态工厂方法：快速构建工具执行失败的简单错误结果对象。
     *
     * <p>创建的实例 success 为 false，仅设置 error 字段（建议包含人类可读的错误描述），
     * 其余字段均为默认值。适用于快速构建不需要详细错误上下文信息的简单失败结果。
     *
     * @param error 错误信息描述字符串，建议包含失败原因以便于排查
     * @return 标记为失败的 ToolExecutionResult 实例，仅包含错误信息
     */
    public static ToolExecutionResult failure(String error) {
        return new ToolExecutionResult(false, "", error, 0L, 0, "", null);
    }

    /**
     * 静态工厂方法：构建工具执行失败的结果对象，并附带工具名称标识。
     *
     * <p>与单参数版本相比，增加了 toolName 字段用于标识是哪个工具产生了失败结果。
     * 这对于错误追踪、日志关联和多工具环境下的故障定位非常有用。建议 error 参数
     * 包含具体的失败原因（如异常消息、错误码等），toolName 参数用于快速定位问题工具。
     *
     * @param error    错误信息描述字符串，建议包含具体失败原因
     * @param toolName 产生此错误的工具名称，用于错误追踪和日志关联
     * @return 标记为失败并携带工具名的 ToolExecutionResult 实例
     */
    public static ToolExecutionResult failure(String error, String toolName) {
        return new ToolExecutionResult(false, "", error, 0L, 0, toolName, null);
    }


    /** @return 执行是否成功 */
    public boolean isSuccess() { return success; }

    /** @return 输出结果 */
    public String getResult() { return result; }

    /** @return 错误信息 */
    public String getError() { return error; }

    /** @return 执行耗时（毫秒） */
    public long getElapsedMs() { return elapsedMs; }

    /** @return Token 消耗量 */
    public int getTokenUsage() { return tokenUsage; }

    /** @return 工具名称 */
    public String getToolName() { return toolName; }

    /** @return 扩展元数据 */
    public Map<String, Object> getMetadata() { return metadata; }

    @Override
    public String toString() {
        return "ToolExecutionResult{success=" + success
                + ", result='" + result + '\''
                + ", error='" + error + '\''
                + ", elapsedMs=" + elapsedMs
                + ", tokenUsage=" + tokenUsage
                + ", toolName='" + toolName + '\''
                + ", metadata=" + metadata + '}';
    }
}
