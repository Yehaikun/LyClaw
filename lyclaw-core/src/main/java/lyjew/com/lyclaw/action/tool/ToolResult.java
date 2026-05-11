package lyjew.com.lyclaw.action.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Deprecated
public class ToolResult {
    private String toolName;
    private boolean success;
    private String output;
    private String errorMessage;
    private long durationMs;
    private Map<String, Object> metadata;

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
