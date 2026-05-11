package lyjew.com.lyclaw.gateway;

import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * 网关健康检查和仪表盘控制器。
 * <p>
 * 提供Kubernetes风格的存活性(liveness)和就绪性(readiness)端点，
 * 以及聚合所有微服务健康状态的仪表盘接口。
 * 通过Nacos DiscoveryClient检查各微服务的注册状态。
 * </p>
 */
@RestController("gatewayHealthController")
public class HealthController {

    private static final Set<String> EXPECTED_SERVICES = Set.of(
            "lyclaw-gateway",
            "lyclaw-orchestration-service",
            "lyclaw-memory-service",
            "lyclaw-plan-service",
            "lyclaw-action-service",
            "lyclaw-reflect-service",
            "lyclaw-protocol-service"
    );

    private static final Map<String, String> SERVICE_DISPLAY = Map.of(
            "lyclaw-gateway", "gateway",
            "lyclaw-orchestration-service", "orchestration",
            "lyclaw-memory-service", "memory",
            "lyclaw-plan-service", "plan",
            "lyclaw-action-service", "action",
            "lyclaw-reflect-service", "reflect",
            "lyclaw-protocol-service", "protocol"
    );

    private final DiscoveryClient discoveryClient;
    private final long startupTime;
    private final String version;

    public HealthController(DiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
        this.startupTime = System.currentTimeMillis();
        this.version = loadVersion();
    }

    /** 从构建信息中加载版本号，无构建信息时返回"dev" */
    private String loadVersion() {
        try (InputStream is = getClass().getResourceAsStream("/META-INF/build-info.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                String v = props.getProperty("build.version");
                if (v != null && !v.isEmpty()) {
                    return v;
                }
            }
        } catch (IOException ignored) {
            // fallback to "dev"
        }
        return "dev";
    }

    /**
     * 仪表盘健康检查：聚合网关及各微服务的健康状态。
     *
     * @return 包含traceId、版本、运行时长及各服务状态的响应
     */
    @GetMapping("/api/dashboard/health")
    public Mono<Map<String, Object>> health() {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        long uptime = System.currentTimeMillis() - startupTime;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("version", version);
        result.put("uptimeMs", uptime);
        result.put("uptime", formatUptime(uptime));

        List<String> registered = discoveryClient.getServices();
        result.put("gateway", buildServiceStatus("gateway", registered));
        result.put("orchestration", buildServiceStatus("orchestration", registered));
        result.put("memory", buildServiceStatus("memory", registered));
        result.put("plan", buildServiceStatus("plan", registered));
        result.put("action", buildServiceStatus("action", registered));
        result.put("reflect", buildServiceStatus("reflect", registered));
        result.put("protocol", buildServiceStatus("protocol", registered));
        result.put("timestamp", System.currentTimeMillis());
        return Mono.just(result);
    }

    /** 网关存活性检查：始终返回UP */
    @GetMapping("/api/gateway/health/liveness")
    public Mono<Map<String, Object>> gatewayLiveness() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("service", "gateway-service");
        return Mono.just(result);
    }

    /** 全局存活性检查 */
    @GetMapping("/api/health/liveness")
    public Mono<Map<String, Object>> liveness() {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("traceId", traceId);
        result.put("timestamp", System.currentTimeMillis());
        return Mono.just(result);
    }

    /**
     * 就绪性检查：检查Nacos连接状态。
     *
     * @return Nacos连接正常时返回UP，否则返回DOWN
     */
    @GetMapping("/api/health/readiness")
    public Mono<Map<String, Object>> readiness() {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> result = new LinkedHashMap<>();

        boolean nacosConnected;
        try {
            List<String> services = discoveryClient.getServices();
            nacosConnected = services != null;
        } catch (Exception e) {
            nacosConnected = false;
        }

        result.put("traceId", traceId);
        result.put("status", nacosConnected ? "UP" : "DOWN");
        result.put("nacos", nacosConnected ? "connected" : "disconnected");
        result.put("timestamp", System.currentTimeMillis());
        return Mono.just(result);
    }

    /**
     * 构建单个服务的健康状态对象。
     *
     * @param displayName       服务显示名称
     * @param registeredServices Nacos中已注册的服务列表
     * @return 包含名称、健康状态的状态Map
     */
    private Map<String, Object> buildServiceStatus(String displayName, List<String> registeredServices) {
        String nacosName = SERVICE_DISPLAY.entrySet().stream()
                .filter(e -> e.getValue().equals(displayName))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("");

        boolean healthy = registeredServices.contains(nacosName);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("name", displayName);
        status.put("healthy", healthy);
        status.put("status", healthy ? "healthy" : "unhealthy");
        return status;
    }

    /**
     * 将毫秒级运行时间格式化为人类可读的字符串。
     *
     * @param uptimeMs 运行时间（毫秒）
     * @return 格式化后的字符串，如"2d 3h 5m"、"1h 30m 45s"
     */
    private String formatUptime(long uptimeMs) {
        long seconds = uptimeMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours % 24, minutes % 60);
        } else if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds % 60);
        } else {
            return String.format("%ds", seconds);
        }
    }
}
