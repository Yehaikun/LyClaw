package lyjew.com.lyclaw.infra.alert;

import lombok.Builder;
import lombok.Data;

/**
 * 告警实体，封装一条由告警管理器生成的告警信息。
 *
 * <p>当{@link MetricsSnapshot}中的指标触发{@link AlertRule}中定义的阈值时，
 * {@link AlertManager}会创建一个 Alert 实例并通过已注册的处理器通知外部系统。
 * 使用 Lombok {@code @Data} 与 {@code @Builder} 简化构造。</p>
 */
@Data
@Builder
public class Alert {

    /** 告警唯一标识 */
    private String alertId;
    /** 告警类型，关联到 AlertRule 中定义的枚举 */
    private AlertRule.AlertType type;
    /** 告警描述信息 */
    private String message;
    /** 触发告警时的实际指标值 */
    private double actualValue;
    /** 告警规则中定义的阈值 */
    private double threshold;
    /** 告警产生时间戳 */
    private long timestamp;
    /** 告警严重级别，如 INFO、WARN、CRITICAL */
    private String severity;
}
