package lyjew.com.lyclaw.reflect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
