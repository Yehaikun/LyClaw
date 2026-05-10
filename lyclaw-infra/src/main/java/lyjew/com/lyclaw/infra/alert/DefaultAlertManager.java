package lyjew.com.lyclaw.infra.alert;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.infra.event.AlertTriggeredEvent;
import lyjew.com.lyclaw.infra.event.InfraEventBus;
import lyjew.com.lyclaw.infra.metrics.MetricsSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Slf4j
@Component
public class DefaultAlertManager implements AlertManager {

    private final Map<AlertRule.AlertType, AlertRule> rules = new ConcurrentHashMap<>();
    private final List<Consumer<Alert>> handlers = new CopyOnWriteArrayList<>();
    private final InfraEventBus eventBus;
    private final List<Long> recentErrorTimestamps = new CopyOnWriteArrayList<>();

    private static final long ERROR_BURST_WINDOW_MS = 60_000;
    private static final int ERROR_BURST_THRESHOLD = 5;
    private final Map<String, AtomicLong> sessionTokens = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sessionToolCalls = new ConcurrentHashMap<>();

    private static final long TOKEN_OVERUSE_THRESHOLD = 100_000L;
    private static final long TOKEN_OVER_LIMIT_THRESHOLD = 1_000_000L;
    private static final long ABNORMAL_BEHAVIOR_TOOL_THRESHOLD = 50L;
    private static final double MEMORY_NEAR_CAPACITY_THRESHOLD = 0.90;

    public DefaultAlertManager(InfraEventBus eventBus) {
        this.eventBus = eventBus;
        defineDefaultRules();
    }

    private void defineDefaultRules() {
        defineRule(AlertRule.builder().type(AlertRule.AlertType.TOKEN_OVERUSE)
                .name("Token overuse > " + TOKEN_OVERUSE_THRESHOLD + " per session")
                .threshold(TOKEN_OVERUSE_THRESHOLD).enabled(true)
                .description("Alerts when a single session consumes more than " + TOKEN_OVERUSE_THRESHOLD + " tokens").build());

        defineRule(AlertRule.builder().type(AlertRule.AlertType.TOKEN_OVER_LIMIT)
                .name("Total token limit exceeded").threshold(TOKEN_OVER_LIMIT_THRESHOLD).enabled(true)
                .description("Alerts when total token consumption exceeds " + TOKEN_OVER_LIMIT_THRESHOLD).build());

        defineRule(AlertRule.builder().type(AlertRule.AlertType.ABNORMAL_BEHAVIOR)
                .name("Abnormal behaviour > " + ABNORMAL_BEHAVIOR_TOOL_THRESHOLD + " tool calls per session")
                .threshold(ABNORMAL_BEHAVIOR_TOOL_THRESHOLD).enabled(true)
                .description("Alerts when a session makes more than " + ABNORMAL_BEHAVIOR_TOOL_THRESHOLD + " tool calls").build());

        defineRule(AlertRule.builder().type(AlertRule.AlertType.ERROR_BURST)
                .name("Error burst > " + ERROR_BURST_THRESHOLD + " errors in 1 minute")
                .threshold(ERROR_BURST_THRESHOLD).enabled(true)
                .description("Alerts when more than " + ERROR_BURST_THRESHOLD + " errors occur within a 1-minute window").build());

        defineRule(AlertRule.builder().type(AlertRule.AlertType.FAILURE_RATE_HIGH)
                .name("Tool failure rate > 30%").threshold(0.30).enabled(true)
                .description("Alerts when the tool failure rate exceeds 30%").build());

        defineRule(AlertRule.builder().type(AlertRule.AlertType.LATENCY_SPIKE)
                .name("Avg LLM latency > 10s").threshold(10_000).enabled(true)
                .description("Alerts when average LLM latency exceeds 10 seconds").build());

        defineRule(AlertRule.builder().type(AlertRule.AlertType.MEMORY_NEAR_CAPACITY)
                .name("Memory usage > 90%").threshold(MEMORY_NEAR_CAPACITY_THRESHOLD).enabled(true)
                .description("Alerts when memory usage exceeds 90% of capacity").build());

        defineRule(AlertRule.builder().type(AlertRule.AlertType.ANOMALOUS_BEHAVIOR)
                .name("Anomalous behaviour detected").threshold(0.95).enabled(true)
                .description("Alerts when behaviour deviates significantly from normal patterns").build());
    }

    @Override
    public void defineRule(AlertRule rule) {
        rules.put(rule.getType(), rule);
        log.info("[AlertManager] Rule defined: {} (type={}, enabled={}, threshold={})",
                rule.getName(), rule.getType(), rule.isEnabled(), rule.getThreshold());
    }

    public AlertRule removeRule(AlertRule.AlertType type) {
        AlertRule removed = rules.remove(type);
        if (removed != null) log.info("[AlertManager] Rule removed: {}", removed.getName());
        return removed;
    }

    public void setRuleEnabled(AlertRule.AlertType type, boolean enabled) {
        AlertRule rule = rules.get(type);
        if (rule != null) {
            rule.setEnabled(enabled);
            log.info("[AlertManager] Rule '{}' {}abled", rule.getName(), enabled ? "en" : "dis");
        }
    }

    public Map<AlertRule.AlertType, AlertRule> getRules() { return Map.copyOf(rules); }

    @Override
    public void onAlert(Consumer<Alert> handler) {
        handlers.add(handler);
        log.debug("[AlertManager] Handler registered (total: {})", handlers.size());
    }

    public void removeHandler(Consumer<Alert> handler) { handlers.remove(handler); }

    @Override
    public void check(MetricsSnapshot snapshot) {
        if (snapshot == null) {
            log.debug("[AlertManager] Skipping check -- snapshot is null");
            return;
        }
        checkTokenOveruse(snapshot);
        checkTokenOverLimit(snapshot);
        checkAbnormalBehavior(snapshot);
        checkErrorBurst(snapshot);
        checkFailureRate(snapshot);
        checkLatencySpike(snapshot);
        checkMemoryCapacity(snapshot);
    }

    private void checkTokenOveruse(MetricsSnapshot snapshot) {
        AlertRule rule = rules.get(AlertRule.AlertType.TOKEN_OVERUSE);
        if (rule == null || !rule.isEnabled()) return;

        for (Map.Entry<String, AtomicLong> entry : sessionTokens.entrySet()) {
            long tokens = entry.getValue().get();
            if (tokens > TOKEN_OVERUSE_THRESHOLD) {
                fireAlert(Alert.builder().alertId(UUID.randomUUID().toString())
                        .type(AlertRule.AlertType.TOKEN_OVERUSE)
                        .message("Session " + entry.getKey() + " consumed " + tokens + " tokens (threshold: " + TOKEN_OVERUSE_THRESHOLD + ")")
                        .actualValue(tokens).threshold(TOKEN_OVERUSE_THRESHOLD)
                        .timestamp(System.currentTimeMillis()).severity("WARN").build());
            }
        }
    }

    private void checkTokenOverLimit(MetricsSnapshot snapshot) {
        AlertRule rule = rules.get(AlertRule.AlertType.TOKEN_OVER_LIMIT);
        if (rule == null || !rule.isEnabled()) return;

        long totalTokens = snapshot.getTotalTokensConsumed();
        if (totalTokens > TOKEN_OVER_LIMIT_THRESHOLD) {
            fireAlert(Alert.builder().alertId(UUID.randomUUID().toString())
                    .type(AlertRule.AlertType.TOKEN_OVER_LIMIT)
                    .message("Total token consumption " + totalTokens + " exceeds limit of " + TOKEN_OVER_LIMIT_THRESHOLD)
                    .actualValue(totalTokens).threshold(TOKEN_OVER_LIMIT_THRESHOLD)
                    .timestamp(System.currentTimeMillis()).severity("WARN").build());
        }
    }

    private void checkAbnormalBehavior(MetricsSnapshot snapshot) {
        AlertRule rule = rules.get(AlertRule.AlertType.ABNORMAL_BEHAVIOR);
        if (rule == null || !rule.isEnabled()) return;

        for (Map.Entry<String, AtomicLong> entry : sessionToolCalls.entrySet()) {
            long calls = entry.getValue().get();
            if (calls > ABNORMAL_BEHAVIOR_TOOL_THRESHOLD) {
                fireAlert(Alert.builder().alertId(UUID.randomUUID().toString())
                        .type(AlertRule.AlertType.ABNORMAL_BEHAVIOR)
                        .message("Session " + entry.getKey() + " made " + calls + " tool calls (threshold: " + ABNORMAL_BEHAVIOR_TOOL_THRESHOLD + ")")
                        .actualValue(calls).threshold(ABNORMAL_BEHAVIOR_TOOL_THRESHOLD)
                        .timestamp(System.currentTimeMillis()).severity("CRITICAL").build());
            }
        }
    }

    private void checkErrorBurst(MetricsSnapshot snapshot) {
        AlertRule rule = rules.get(AlertRule.AlertType.ERROR_BURST);
        if (rule == null || !rule.isEnabled()) return;

        long windowStart = System.currentTimeMillis() - ERROR_BURST_WINDOW_MS;
        recentErrorTimestamps.removeIf(ts -> ts < windowStart);

        int errorCount = recentErrorTimestamps.size();
        if (errorCount > ERROR_BURST_THRESHOLD) {
            fireAlert(Alert.builder().alertId(UUID.randomUUID().toString())
                    .type(AlertRule.AlertType.ERROR_BURST)
                    .message(errorCount + " errors in the last minute (threshold: " + ERROR_BURST_THRESHOLD + ")")
                    .actualValue(errorCount).threshold(ERROR_BURST_THRESHOLD)
                    .timestamp(System.currentTimeMillis()).severity("CRITICAL").build());
        }
    }

    private void checkFailureRate(MetricsSnapshot snapshot) {
        AlertRule rule = rules.get(AlertRule.AlertType.FAILURE_RATE_HIGH);
        if (rule == null || !rule.isEnabled()) return;

        if (snapshot.getTotalToolCalls() > 0) {
            double failureRate = (double) snapshot.getFailedToolCalls() / snapshot.getTotalToolCalls();
            if (failureRate > rule.getThreshold()) {
                fireAlert(Alert.builder().alertId(UUID.randomUUID().toString())
                        .type(AlertRule.AlertType.FAILURE_RATE_HIGH)
                        .message("Tool failure rate " + formatPercent(failureRate) + " exceeds threshold of " + formatPercent(rule.getThreshold()))
                        .actualValue(failureRate).threshold(rule.getThreshold())
                        .timestamp(System.currentTimeMillis()).severity("WARN").build());
            }
        }
    }

    private void checkLatencySpike(MetricsSnapshot snapshot) {
        AlertRule rule = rules.get(AlertRule.AlertType.LATENCY_SPIKE);
        if (rule == null || !rule.isEnabled()) return;

        if (snapshot.getAvgLlmLatencyMs() > rule.getThreshold()) {
            fireAlert(Alert.builder().alertId(UUID.randomUUID().toString())
                    .type(AlertRule.AlertType.LATENCY_SPIKE)
                    .message("Avg LLM latency " + String.format("%.0fms", snapshot.getAvgLlmLatencyMs()) + " exceeds threshold of " + String.format("%.0fms", rule.getThreshold()))
                    .actualValue(snapshot.getAvgLlmLatencyMs()).threshold(rule.getThreshold())
                    .timestamp(System.currentTimeMillis()).severity("WARN").build());
        }
    }

    private void checkMemoryCapacity(MetricsSnapshot snapshot) {
        AlertRule rule = rules.get(AlertRule.AlertType.MEMORY_NEAR_CAPACITY);
        if (rule == null || !rule.isEnabled()) return;

        double memoryUsageEstimate = estimateMemoryUsage(snapshot);
        if (memoryUsageEstimate > MEMORY_NEAR_CAPACITY_THRESHOLD) {
            fireAlert(Alert.builder().alertId(UUID.randomUUID().toString())
                    .type(AlertRule.AlertType.MEMORY_NEAR_CAPACITY)
                    .message("Memory usage " + formatPercent(memoryUsageEstimate) + " exceeds " + formatPercent(MEMORY_NEAR_CAPACITY_THRESHOLD))
                    .actualValue(memoryUsageEstimate).threshold(MEMORY_NEAR_CAPACITY_THRESHOLD)
                    .timestamp(System.currentTimeMillis()).severity("WARN").build());
        }
    }

    public void recordSessionTokens(String sessionId, long tokens) {
        sessionTokens.computeIfAbsent(sessionId, k -> new AtomicLong()).addAndGet(tokens);
    }

    public void recordSessionToolCall(String sessionId) {
        sessionToolCalls.computeIfAbsent(sessionId, k -> new AtomicLong()).incrementAndGet();
    }

    public void recordError() {
        recentErrorTimestamps.add(System.currentTimeMillis());
    }

    public void resetSession(String sessionId) {
        sessionTokens.remove(sessionId);
        sessionToolCalls.remove(sessionId);
        log.debug("[AlertManager] Session reset: {}", sessionId);
    }

    private void fireAlert(Alert alert) {
        log.warn("[AlertManager] ALERT [{}] {}: {} (actual={}, threshold={})",
                alert.getSeverity(), alert.getType(), alert.getMessage(),
                alert.getActualValue(), alert.getThreshold());

        for (Consumer<Alert> handler : handlers) {
            try { handler.accept(alert); }
            catch (Exception e) { log.error("[AlertManager] Handler error for alert {}", alert.getAlertId(), e); }
        }

        eventBus.publishAsync(new AlertTriggeredEvent(
                "AlertManager", alert.getAlertId(), alert.getType(),
                alert.getMessage(), alert.getActualValue(), alert.getThreshold()));
    }

    private String formatPercent(double value) {
        return String.format("%.1f%%", value * 100);
    }

    private double estimateMemoryUsage(MetricsSnapshot snapshot) {
        int stageCount = snapshot.getStageDurations() != null ? snapshot.getStageDurations().size() : 0;
        return Math.min(0.95, stageCount * 0.05);
    }

    public int getRecentErrorCount() {
        long windowStart = System.currentTimeMillis() - ERROR_BURST_WINDOW_MS;
        return (int) recentErrorTimestamps.stream().filter(ts -> ts >= windowStart).count();
    }

    public long getSessionTokens(String sessionId) {
        AtomicLong counter = sessionTokens.get(sessionId);
        return counter != null ? counter.get() : 0;
    }

    public long getSessionToolCalls(String sessionId) {
        AtomicLong counter = sessionToolCalls.get(sessionId);
        return counter != null ? counter.get() : 0;
    }
}
