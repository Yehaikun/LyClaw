package lyjew.com.lyclaw.infra.event;

import lyjew.com.lyclaw.event.Event;
import lyjew.com.lyclaw.infra.alert.AlertRule;

/**
 * 告警触发事件，在系统检测到异常并触发告警规则时发布。
 *
 * <p>携带告警 ID、告警类型、告警消息、实际值和阈值，
 * 用于通知外部告警通道（如日志、消息队列、Webhook 等）。</p>
 */
public class AlertTriggeredEvent extends Event {

    /** 告警唯一标识 */
    private final String alertId;
    /** 告警类型 */
    private final AlertRule.AlertType alertType;
    /** 告警消息描述 */
    private final String message;
    /** 实际观测值 */
    private final double actualValue;
    /** 触发阈值 */
    private final double threshold;

    /**
     * 构造一个告警触发事件。
     *
     * @param source      事件来源标识
     * @param alertId     告警 ID
     * @param alertType   告警类型
     * @param message     告警消息
     * @param actualValue 实际值
     * @param threshold   阈值
     */
    public AlertTriggeredEvent(String source, String alertId, AlertRule.AlertType alertType,
                               String message, double actualValue, double threshold) {
        super(source, "ALERT_TRIGGERED");
        this.alertId = alertId;
        this.alertType = alertType;
        this.message = message;
        this.actualValue = actualValue;
        this.threshold = threshold;
    }

    /** @return 告警 ID */
    public String getAlertId() { return alertId; }
    /** @return 告警类型 */
    public AlertRule.AlertType getAlertType() { return alertType; }
    /** @return 告警消息 */
    public String getMessage() { return message; }
    /** @return 实际值 */
    public double getActualValue() { return actualValue; }
    /** @return 阈值 */
    public double getThreshold() { return threshold; }
}
