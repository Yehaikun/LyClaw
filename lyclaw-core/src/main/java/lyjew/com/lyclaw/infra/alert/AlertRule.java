package lyjew.com.lyclaw.infra.alert;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AlertRule {

    public enum AlertType {
        TOKEN_OVER_LIMIT,
        FAILURE_RATE_HIGH,
        LATENCY_SPIKE,
        ANOMALOUS_BEHAVIOR,
        MEMORY_NEAR_CAPACITY
    }

    private AlertType type;
    private String name;
    private double threshold;
    private boolean enabled;
    private String description;
}
