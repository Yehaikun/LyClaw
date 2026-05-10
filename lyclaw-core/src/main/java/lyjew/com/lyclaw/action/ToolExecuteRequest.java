package lyjew.com.lyclaw.action;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class ToolExecuteRequest {
    private String toolName;
    private Map<String, Object> args;
    private String sandboxLevel;
    private String sessionId;
}
