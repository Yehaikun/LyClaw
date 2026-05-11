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

/**
 * 默认告警管理器，基于预定义规则检测运行时异常并触发告警。
 *
 * <p>支持的告警类型：
 * <ul>
 *   <li><b>TOKEN_OVERUSE</b>：单会话 Token 消耗超过 100,000</li>
 *   <li><b>TOKEN_OVER_LIMIT</b>：总 Token 消耗超过 1,000,000</li>
 *   <li><b>ABNORMAL_BEHAVIOR</b>：单会话工具调用超过 50 次</li>
 *   <li><b>ERROR_BURST</b>：1 分钟内错误超过 5 次</li>
 *   <li><b>FAILURE_RATE_HIGH</b>：工具失败率超过 30%</li>
 *   <li><b>LATENCY_SPIKE</b>：平均 LLM 延迟超过 10 秒</li>
 *   <li><b>MEMORY_NEAR_CAPACITY</b>：内存使用超过 90%</li>
 *   <li><b>ANOMALOUS_BEHAVIOR</b>：行为异常检测</li>
 * </ul>
 * </p>
 *
 * <p>告警触发后通过注册的 handler 列表和 {@link InfraEventBus} 异步分发。</p>
 */
@Slf4j
@Component
public class DefaultAlertManager implements AlertManager {

    /** 告警规则注册表 */
    private final Map<AlertRule.AlertType, AlertRule> rules = new ConcurrentHashMap<>();
    /** 告警处理器列表 */
    private final List<Consumer<Alert>> handlers = new CopyOnWriteArrayList<>();
    private final InfraEventBus eventBus;
    /** 最近错误时间戳列表（用于错误爆发检测） */
    private final List<Long> recentErrorTimestamps = new CopyOnWriteArrayList<>();

    /** 错误爆发检测窗口（毫秒） */
    private static final long ERROR_BURST_WINDOW_MS = 60_000;
    /** 错误爆发阈值 */
    private static final int ERROR_BURST_THRESHOLD = 5;
    /** 按会话统计的 Token 消耗 */
    private final Map<String, AtomicLong> sessionTokens = new ConcurrentHashMap<>();
    /** 按会话统计的工具调用次数 */
    private final Map<String, AtomicLong> sessionToolCalls = new ConcurrentHashMap<>();

    /** Token 过度使用阈值（单会话） */
    private static final long TOKEN_OVERUSE_THRESHOLD = 100_000L;
    /** Token 总量限制阈值 */
    private static final long TOKEN_OVER_LIMIT_THRESHOLD = 1_000_000L;
    /** 异常行为工具调用阈值 */
    private static final long ABNORMAL_BEHAVIOR_TOOL_THRESHOLD = 50L;
    /** 内存接近容量阈值（90%） */
    private static final double MEMORY_NEAR_CAPACITY_THRESHOLD = 0.90;

    public DefaultAlertManager(InfraEventBus eventBus) {
        this.eventBus = eventBus;
        defineDefaultRules();
    }

    /** 定义所有默认告警规则 */
    private void defineDefaultRules() {
        // Token 过度使用
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

    /**
     * 注册告警规则。同类型规则会覆盖。
     *
     * @param rule 告警规则
     */
    @Override
    public void defineRule(AlertRule rule) {
        rules.put(rule.getType(), rule);
        log.info("[AlertManager] Rule defined: {} (type={}, enabled={}, threshold={})",
                rule.getName(), rule.getType(), rule.isEnabled(), rule.getThreshold());
    }

    /** 移除指定类型的告警规则 */
    public AlertRule removeRule(AlertRule.AlertType type) {
        AlertRule removed = rules.remove(type);
        if (removed != null) log.info("[AlertManager] Rule removed: {}", removed.getName());
        return removed;
    }

    /** 启用/禁用指定类型的告警规则 */
    public void setRuleEnabled(AlertRule.AlertType type, boolean enabled) {
        AlertRule rule = rules.get(type);
        if (rule != null) {
            rule.setEnabled(enabled);
            log.info("[AlertManager] Rule '{}' {}abled", rule.getName(), enabled ? "en" : "dis");
        }
    }

    public Map<AlertRule.AlertType, AlertRule> getRules() { return Map.copyOf(rules); }

    /**
     * 注册告警处理器。每次告警触发时会调用所有已注册的处理器。
     */
    @Override
    public void onAlert(Consumer<Alert> handler) {
        handlers.add(handler);
        log.debug("[AlertManager] Handler registered (total: {})", handlers.size());
    }

    public void removeHandler(Consumer<Alert> handler) { handlers.remove(handler); }

    /**
     * 根据当前指标快照检查所有启用的告警规则。
     * 对每条规则，如果条件满足则触发告警。
     */
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

    /** 检查单会话 Token 过度使用 */
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

    /** 检查总 Token 消耗是否超过全局限制 */
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

    /** 检查单会话工具调用次数是否异常 */
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

    /** 检查滑动窗口内的错误爆发 */
    private void checkErrorBurst(MetricsSnapshot snapshot) {
        AlertRule rule = rules.get(AlertRule.AlertType.ERROR_BURST);
        if (rule == null || !rule.isEnabled()) return;

        // 清理过期的时间戳
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

    /** 检查工具失败率是否过高 */
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

    /** 检查 LLM 平均延迟是否异常飙升 */
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

    /** 检查内存使用是否接近容量上限 */
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

    /** 记录会话的 Token 消耗 */
    public void recordSessionTokens(String sessionId, long tokens) {
        sessionTokens.computeIfAbsent(sessionId, k -> new AtomicLong()).addAndGet(tokens);
    }

    /** 记录会话的工具调用 */
    public void recordSessionToolCall(String sessionId) {
        sessionToolCalls.computeIfAbsent(sessionId, k -> new AtomicLong()).incrementAndGet();
    }

    /** 记录一次错误（带时间戳，用于错误爆发检测） */
    public void recordError() {
        recentErrorTimestamps.add(System.currentTimeMillis());
    }

    /** 重置会话的 Token 和工具调用统计 */
    public void resetSession(String sessionId) {
        sessionTokens.remove(sessionId);
        sessionToolCalls.remove(sessionId);
        log.debug("[AlertManager] Session reset: {}", sessionId);
    }

    /**
     * 触发告警：记录日志，通知所有处理器，并通过 EventBus 异步发布事件。
     */
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

    /** 将小数格式化为百分比字符串 */
    private String formatPercent(double value) {
        return String.format("%.1f%%", value * 100);
    }

    /** 估算当前内存使用率（基于阶段数的简化估算，上限 95%） */
    private double estimateMemoryUsage(MetricsSnapshot snapshot) {
        int stageCount = snapshot.getStageDurations() != null ? snapshot.getStageDurations().size() : 0;
        return Math.min(0.95, stageCount * 0.05);
    }

    /** @return 最近 1 分钟内的错误数 */
    public int getRecentErrorCount() {
        long windowStart = System.currentTimeMillis() - ERROR_BURST_WINDOW_MS;
        return (int) recentErrorTimestamps.stream().filter(ts -> ts >= windowStart).count();
    }

    /** @return 指定会话的 Token 消耗量 */
    public long getSessionTokens(String sessionId) {
        AtomicLong counter = sessionTokens.get(sessionId);
        return counter != null ? counter.get() : 0;
    }

    /** @return 指定会话的工具调用次数 */
    public long getSessionToolCalls(String sessionId) {
        AtomicLong counter = sessionToolCalls.get(sessionId);
        return counter != null ? counter.get() : 0;
    }
}
