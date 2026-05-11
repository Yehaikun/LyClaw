package lyjew.com.lyclaw.infra.alert;

import lyjew.com.lyclaw.infra.metrics.MetricsSnapshot;

import java.util.function.Consumer;

/**
 * 告警管理器接口，定义告警规则注册、指标检查与告警通知的规范。
 *
 * <p>实现类维护一组{@link AlertRule}，定期或在指标更新时调用{@link #check(MetricsSnapshot)}
 * 对当前快照进行阈值比对，若触发规则则通过已注册的{@link #onAlert}处理器分发
 * {@link Alert} 实例给外部系统（如日志、消息队列、监控平台）。</p>
 */
public interface AlertManager {

    /**
     * 注册一条告警规则。
     *
     * @param rule 告警规则定义
     */
    void defineRule(AlertRule rule);

    /**
     * 对指定指标快照执行所有启用规则的检查，触发符合条件的告警。
     *
     * @param snapshot 当前指标快照
     */
    void check(MetricsSnapshot snapshot);

    /**
     * 注册告警处理器，每当有告警触发时回调该处理器。
     *
     * @param handler 接收 Alert 实例的消费者
     */
    void onAlert(Consumer<Alert> handler);
}
