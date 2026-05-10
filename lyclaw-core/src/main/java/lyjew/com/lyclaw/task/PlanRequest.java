package lyjew.com.lyclaw.task;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class PlanRequest {
    private String sessionId;
    private String userIntent;
    private String strategy;
    private Map<String, Object> context;
}
