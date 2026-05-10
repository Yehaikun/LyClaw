package lyjew.com.lyclaw.infra.alert;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AlertRule {

    public enum AlertType {
        TOKEN_OVER_LIMIT,
        TOKEN_OVERUSE,
        FAILURE_RATE_HIGH,
        LATENCY_SPIKE,
        ABNORMAL_BEHAVIOR,
        ERROR_BURST,
        ANOMALOUS_BEHAVIOR,
        MEMORY_NEAR_CAPACITY
    }

    private AlertType type;
    private String name;
    private double threshold;
    private boolean enabled;
    private String description;
}
