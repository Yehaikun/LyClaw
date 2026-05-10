package lyjew.com.lyclaw.infra.alert;

import lyjew.com.lyclaw.infra.event.AlertTriggeredEvent;
import lyjew.com.lyclaw.infra.event.InfraEventBus;
import lyjew.com.lyclaw.infra.metrics.MetricsSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class DefaultAlertManager implements AlertManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultAlertManager.class);

    private final Map<AlertRule.AlertType, AlertRule> rules = new ConcurrentHashMap<>();
    private final List<Consumer<Alert>> handlers = new CopyOnWriteArrayList<>();
    private final InfraEventBus eventBus;

    public DefaultAlertManager(InfraEventBus eventBus) {
        this.eventBus = eventBus;
        defineDefaultRules();
    }

    private void defineDefaultRules() {
        defineRule(AlertRule.builder()
                .type(AlertRule.AlertType.FAILURE_RATE_HIGH)
                .name("Tool failure rate > 30%")
                .threshold(0.30)
                .enabled(true)
                .build());
        defineRule(AlertRule.builder()
                .type(AlertRule.AlertType.LATENCY_SPIKE)
                .name("Avg LLM latency > 10s")
                .threshold(10000)
                .enabled(true)
                .build());
    }

    @Override
    public void defineRule(AlertRule rule) {
        rules.put(rule.getType(), rule);
        log.info("[AlertManager] Rule defined: {} (enabled={})", rule.getName(), rule.isEnabled());
    }

    @Override
    public void check(MetricsSnapshot snapshot) {
        // 检查失败率
        AlertRule failureRule = rules.get(AlertRule.AlertType.FAILURE_RATE_HIGH);
        if (failureRule != null && failureRule.isEnabled() && snapshot.getTotalToolCalls() > 0) {
            double failureRate = (double) snapshot.getFailedToolCalls() / snapshot.getTotalToolCalls();
            if (failureRate > failureRule.getThreshold()) {
                Alert alert = Alert.builder()
                        .alertId(UUID.randomUUID().toString())
                        .type(AlertRule.AlertType.FAILURE_RATE_HIGH)
                        .message("Tool failure rate " + String.format("%.1f%%", failureRate * 100) + " exceeds threshold")
                        .actualValue(failureRate)
                        .threshold(failureRule.getThreshold())
                        .timestamp(System.currentTimeMillis())
                        .severity("WARN")
                        .build();
                fireAlert(alert);
            }
        }

        // 检查延迟
        AlertRule latencyRule = rules.get(AlertRule.AlertType.LATENCY_SPIKE);
        if (latencyRule != null && latencyRule.isEnabled()) {
            if (snapshot.getAvgLlmLatencyMs() > latencyRule.getThreshold()) {
                Alert alert = Alert.builder()
                        .alertId(UUID.randomUUID().toString())
                        .type(AlertRule.AlertType.LATENCY_SPIKE)
                        .message("Avg LLM latency " + String.format("%.0fms", snapshot.getAvgLlmLatencyMs()) + " exceeds threshold")
                        .actualValue(snapshot.getAvgLlmLatencyMs())
                        .threshold(latencyRule.getThreshold())
                        .timestamp(System.currentTimeMillis())
                        .severity("WARN")
                        .build();
                fireAlert(alert);
            }
        }
    }

    @Override
    public void onAlert(Consumer<Alert> handler) {
        handlers.add(handler);
    }

    private void fireAlert(Alert alert) {
        log.warn("[AlertManager] ALERT: {} - {}", alert.getType(), alert.getMessage());
        for (Consumer<Alert> handler : handlers) {
            try {
                handler.accept(alert);
            } catch (Exception e) {
                log.error("[AlertManager] Handler error", e);
            }
        }
        eventBus.publishAsync(new AlertTriggeredEvent(
                "AlertManager", alert.getAlertId(), alert.getType(),
                alert.getMessage(), alert.getActualValue(), alert.getThreshold()));
    }
}
