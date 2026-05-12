package lyjew.com.lyclaw.tool;

import java.util.HashMap;
import java.util.Map;

/**
 * 框架层的工具执行结果封装，作为新框架与旧 action 模块之间的双向转换桥梁。
 *
 * <p>同时保存成功结果（result）和失败信息（error），通过 {@link #isSuccess()} 判断执行状态。
 * 核心职责是提供 {@link #fromLegacyActionResult(Object)} 和 {@link #toLegacyActionResult()}
 * 两组反射转换方法，使新旧两套 ToolResult 类型可以互操作，便于渐进式迁移。</p>
 *
 * <p>构造器全参数，建议通过静态工厂方法 {@link #success(String)} / {@link #failure(String)} 创建实例。</p>
 */
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

    /**
     * 通过反射将旧 action 模块的 ToolResult 转换为框架层 ToolResult。
     *
     * <p>通过 Java 反射调用旧对象的 isSuccess/getOutput/getErrorMessage/getDurationMs/getToolName/getMetadata
     * 方法提取字段值，零编译依赖实现模块解耦。转换过程如出现异常则返回 failure 结果标记转换失败原因。</p>
     *
     * @param legacy 旧 action 模块的 ToolResult 实例
     * @return 框架层 ToolResult，legacy 为 null 时返回 failure
     */
    public static ToolExecutionResult fromLegacyActionResult(Object legacy) {
        if (legacy == null) {
            return failure("null legacy result");
        }
        try {
            Class<?> c = legacy.getClass();
            // 逐个反射调用 getter 提取字段值
            boolean success = (boolean) c.getMethod("isSuccess").invoke(legacy);
            String output = (String) c.getMethod("getOutput").invoke(legacy);
            String errorMsg = (String) c.getMethod("getErrorMessage").invoke(legacy);
            long duration = (long) c.getMethod("getDurationMs").invoke(legacy);
            String name = (String) c.getMethod("getToolName").invoke(legacy);
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) c.getMethod("getMetadata").invoke(legacy);
            return new ToolExecutionResult(success,
                    output != null ? output : "",
                    errorMsg != null ? errorMsg : "",
                    duration, 0,
                    name != null ? name : "",
                    meta);
        } catch (Exception e) {
            return failure("Failed to convert legacy result: " + e.getMessage());
        }
    }

    /**
     * 通过反射将当前框架层 ToolResult 转换为旧 action 模块的 ToolResult。
     *
     * <p>使用 builder 模式构造旧对象：先通过 Class.forName 加载旧 ToolResult 类，再反射调用
     * builder() 工厂方法创建 builder，逐个设置字段，最后调用 build() 产出实例。
     * 整个过程零编译依赖旧模块。</p>
     *
     * @return 旧 action 模块的 ToolResult 实例
     * @throws RuntimeException 反射操作失败时抛出
     */
    public Object toLegacyActionResult() {
        try {
            // 通过反射加载旧模块类型并走 builder 模式构建
            Class<?> c = Class.forName("lyjew.com.lyclaw.action.tool.ToolResult");
            Object builder = c.getMethod("builder").invoke(null);
            builder.getClass().getMethod("toolName", String.class).invoke(builder, this.toolName);
            builder.getClass().getMethod("success", boolean.class).invoke(builder, this.success);
            builder.getClass().getMethod("output", String.class).invoke(builder, this.result);
            builder.getClass().getMethod("errorMessage", String.class).invoke(builder, this.error);
            builder.getClass().getMethod("durationMs", long.class).invoke(builder, this.elapsedMs);
            builder.getClass().getMethod("metadata", Map.class).invoke(builder, new HashMap<>(this.metadata));
            return builder.getClass().getMethod("build").invoke(builder);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert to legacy action result: " + e.getMessage(), e);
        }
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
        return "ToolResult{success=" + success
                + ", result='" + result + '\''
                + ", error='" + error + '\''
                + ", elapsedMs=" + elapsedMs
                + ", tokenUsage=" + tokenUsage
                + ", toolName='" + toolName + '\''
                + ", metadata=" + metadata + '}';
    }
}
