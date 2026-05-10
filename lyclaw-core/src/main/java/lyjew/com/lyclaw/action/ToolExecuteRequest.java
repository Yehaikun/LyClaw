package lyjew.com.lyclaw.action;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecuteRequest {
    private String toolName;
    private Map<String, Object> args;
    private String sandboxLevel;
    private String sessionId;
}
