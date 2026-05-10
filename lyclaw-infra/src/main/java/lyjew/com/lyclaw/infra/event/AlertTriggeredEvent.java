package lyjew.com.lyclaw.infra.event;

import lyjew.com.lyclaw.event.Event;
import lyjew.com.lyclaw.infra.alert.AlertRule;

public class AlertTriggeredEvent extends Event {

    private final String alertId;
    private final AlertRule.AlertType alertType;
    private final String message;
    private final double actualValue;
    private final double threshold;

    public AlertTriggeredEvent(String source, String alertId, AlertRule.AlertType alertType,
                               String message, double actualValue, double threshold) {
        super(source, "ALERT_TRIGGERED");
        this.alertId = alertId;
        this.alertType = alertType;
        this.message = message;
        this.actualValue = actualValue;
        this.threshold = threshold;
    }

    public String getAlertId() { return alertId; }
    public AlertRule.AlertType getAlertType() { return alertType; }
    public String getMessage() { return message; }
    public double getActualValue() { return actualValue; }
    public double getThreshold() { return threshold; }
}
