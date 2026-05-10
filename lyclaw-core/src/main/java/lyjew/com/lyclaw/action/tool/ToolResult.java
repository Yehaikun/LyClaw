package lyjew.com.lyclaw.action.tool;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class ToolResult {

    private String toolName;
    private boolean success;
    private String output;
    private String errorMessage;
    private long durationMs;
    private Map<String, Object> metadata;
}
