package lyjew.com.lyclaw.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanRequest {
    private String sessionId;
    private String userIntent;
    private String strategy;
    private Map<String, Object> context;
}
