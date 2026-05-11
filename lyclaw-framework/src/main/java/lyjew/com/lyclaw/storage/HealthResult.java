package lyjew.com.lyclaw.storage;

import java.util.Map;

/**
 * 存储后端健康检查结果。
 *
 * @param healthy 是否健康
 * @param message 状态描述
 * @param details 详细指标（如连接数、延迟等）
 */
public record HealthResult(boolean healthy, String message, Map<String, Object> details) {

    public static HealthResult up(String message) {
        return new HealthResult(true, message, Map.of());
    }

    public static HealthResult down(String message) {
        return new HealthResult(false, message, Map.of());
    }

    public static HealthResult up(String message, Map<String, Object> details) {
        return new HealthResult(true, message, details);
    }

    public static HealthResult down(String message, Map<String, Object> details) {
        return new HealthResult(false, message, details);
    }
}
