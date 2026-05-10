package lyjew.com.lyclaw.infra.alert;

import lyjew.com.lyclaw.infra.metrics.MetricsSnapshot;

import java.util.function.Consumer;

public interface AlertManager {

    void defineRule(AlertRule rule);

    void check(MetricsSnapshot snapshot);

    void onAlert(Consumer<Alert> handler);
}
