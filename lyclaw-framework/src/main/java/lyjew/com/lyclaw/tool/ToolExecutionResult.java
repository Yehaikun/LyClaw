package lyjew.com.lyclaw.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具执行结果封装，框架中唯一的工具结果类型。
 *
 * <p>通过 {@link #isSuccess()} 判断执行状态，构造器自动处理 null 值（转空字符串/空 Map）。
 * 建议通过静态工厂方法或 Builder 创建实例。
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

    /** 快速构建成功结果（无工具名、无耗时、无额外元数据）。 */
    public static ToolExecutionResult success(String result) {
        return new ToolExecutionResult(true, result, "", 0L, 0, "", null);
    }

    /** 快速构建成功结果，附带工具名。 */
    public static ToolExecutionResult success(String result, String toolName) {
        return new ToolExecutionResult(true, result, "", 0L, 0, toolName, null);
    }

    /** 快速构建失败结果。 */
    public static ToolExecutionResult failure(String error) {
        return new ToolExecutionResult(false, "", error, 0L, 0, "", null);
    }

    /** 快速构建失败结果，附带工具名。 */
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
