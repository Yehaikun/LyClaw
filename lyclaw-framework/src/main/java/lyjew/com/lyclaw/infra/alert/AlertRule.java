package lyjew.com.lyclaw.infra.alert;

import lombok.Builder;
import lombok.Data;

/**
 * 告警规则定义，描述一条告警的触发条件与元数据。
 *
 * <p>每条规则指定一个告警类型、阈值和启用状态。{@link AlertManager}在每次
 * {@link MetricsSnapshot} 更新时遍历启用的规则，若指标超过阈值则触发告警。
 * 使用 Lombok {@code @Data} 与 {@code @Builder} 简化构造。</p>
 */
@Data
@Builder
public class AlertRule {

    /**
     * 告警类型枚举，定义框架支持的告警类别。
     */
    public enum AlertType {
        /** Token 超过单次限制 */
        TOKEN_OVER_LIMIT,
        /** Token 用量过高 */
        TOKEN_OVERUSE,
        /** 失败率过高 */
        FAILURE_RATE_HIGH,
        /** 延迟突增 */
        LATENCY_SPIKE,
        /** 异常行为 */
        ABNORMAL_BEHAVIOR,
        /** 错误爆发 */
        ERROR_BURST,
        /** 行为异常（通用） */
        ANOMALOUS_BEHAVIOR,
        /** 记忆存储接近容量上限 */
        MEMORY_NEAR_CAPACITY
    }

    /** 告警类型 */
    private AlertType type;
    /** 规则名称 */
    private String name;
    /** 触发阈值 */
    private double threshold;
    /** 是否启用 */
    private boolean enabled;
    /** 规则描述 */
    private String description;
}
