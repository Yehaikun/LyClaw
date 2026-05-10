package lyjew.com.lyclaw.action;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class ActionResult {
    private String nodeId;
    private boolean success;
    private String output;
    private String errorMessage;
    private long durationMs;
    private Map<String, Object> metadata;
}
