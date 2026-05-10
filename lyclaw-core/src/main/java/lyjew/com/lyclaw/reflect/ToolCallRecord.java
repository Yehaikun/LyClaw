package lyjew.com.lyclaw.reflect;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class ToolCallRecord {

    private String toolName;
    private Map<String, Object> args;
    private String output;
    private boolean success;
    private long durationMs;
    private long timestamp;
}
