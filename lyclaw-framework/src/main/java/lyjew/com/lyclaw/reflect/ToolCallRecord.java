package lyjew.com.lyclaw.reflect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具调用记录实体，记录单次工具调用的详细信息。
 * 用于反思引擎分析工具调用的成功/失败模式。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallRecord {
    private String toolName;
    private boolean success;
    private long durationMs;
    private String output;
    private String errorMessage;
}
