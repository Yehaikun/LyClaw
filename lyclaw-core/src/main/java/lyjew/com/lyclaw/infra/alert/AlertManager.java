package lyjew.com.lyclaw.infra.alert;

import lyjew.com.lyclaw.infra.metrics.MetricsSnapshot;
import java.util.function.Consumer;

/**
 * 告警管理器 —— 定义告警规则并在指标异常时触发告警。
 *
 * @since 2.0
 */
public interface AlertManager {

    void defineRule(AlertRule rule);

    void check(MetricsSnapshot snapshot);

    void onAlert(Consumer<Alert> handler);
}
