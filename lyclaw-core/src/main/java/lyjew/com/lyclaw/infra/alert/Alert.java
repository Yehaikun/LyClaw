package lyjew.com.lyclaw.infra.alert;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Alert {

    private String alertId;
    private AlertRule.AlertType type;
    private String message;
    private double actualValue;
    private double threshold;
    private long timestamp;
    private String severity;
}
