package lyjew.com.lyclaw.action.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工具执行结果封装，包含工具调用的输出、错误、耗时等元数据。
 *
 * <p>作为工具执行的返回载体，在旧版工具体系（已废弃）中使用。提供了
 * {@link #toFrameworkResult()}和{@link #fromFrameworkResult}两个转换方法，
 * 用于与新版框架的{@link lyjew.com.lyclaw.framework.tool.ToolResult}互转。
 * 使用 Lombok {@code @Data}、{@code @Builder} 等注解简化构造。</p>
 *
 * @deprecated 请使用 {@link lyjew.com.lyclaw.framework.tool.ToolResult} 替代。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Deprecated
public class ToolResult {
    /** 工具名称 */
    private String toolName;
    /** 执行是否成功 */
    private boolean success;
    /** 工具输出内容 */
    private String output;
    /** 错误信息（仅失败时有效） */
    private String errorMessage;
    /** 执行耗时（毫秒） */
    private long durationMs;
    /** 附加元数据 */
    private Map<String, Object> metadata;

    /**
     * 将当前结果转换为新版框架的 ToolResult 格式。
     *
     * @return 新版框架兼容的工具执行结果
     */
    public lyjew.com.lyclaw.framework.tool.ToolResult toFrameworkResult() {
        return new lyjew.com.lyclaw.framework.tool.ToolResult(
                this.success,
                this.output != null ? this.output : "",
                this.errorMessage != null ? this.errorMessage : "",
                this.durationMs,
                0,
                this.toolName != null ? this.toolName : "",
                this.metadata);
    }

    /**
     * 从新版框架的 ToolResult 创建当前（旧版）结果对象。
     *
     * @param framework 新版框架的工具执行结果
     * @return 当前旧版兼容的工具执行结果
     */
    public static ToolResult fromFrameworkResult(
            lyjew.com.lyclaw.framework.tool.ToolResult framework) {
        return ToolResult.builder()
                .toolName(framework.getToolName())
                .success(framework.isSuccess())
                .output(framework.getResult())
                .errorMessage(framework.getError())
                .durationMs(framework.getElapsedMs())
                .metadata(framework.getMetadata())
                .build();
    }
}
