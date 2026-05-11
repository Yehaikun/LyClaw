package lyjew.com.lyclaw.action;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 健康检查 REST 控制器，用于 Kubernetes 探针和服务状态监控。
 *
 * <p>提供三个健康检查端点，适配 Kubernetes 的探针机制：
 * <ul>
 *   <li>{@code /api/action/health/liveness} -- 存活探针，仅检查服务进程是否运行</li>
 *   <li>{@code /api/action/health/readiness} -- 就绪探针，包含服务启动时间</li>
 *   <li>{@code /api/action/health} -- 综合健康检查，返回 traceId 和运行时长</li>
 * </ul>
 * </p>
 */
@RestController("actionHealthController")
public class HealthController {

    /** 服务名称标识 */
    private final String serviceName;
    /** 服务启动时刻，用于计算 uptime */
    private final Instant startTime = Instant.now();

    public HealthController() {
        this.serviceName = "action-service";
    }

    /**
     * 存活探针（Liveness Probe）。
     *
     * <p>Kubernetes 用于判断容器是否需要重启。仅返回服务状态为 UP。</p>
     *
     * @return 包含 status 和 service 的 Map
     */
    @GetMapping("/api/action/health/liveness")
    public Mono<Map<String, Object>> liveness() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", serviceName);
        return Mono.just(status);
    }

    /**
     * 就绪探针（Readiness Probe）。
     *
     * <p>Kubernetes 用于判断流量是否可以路由到该 Pod。
     * 包含服务运行时长（秒）。</p>
     *
     * @return 包含 status、service、uptime 的 Map
     */
    @GetMapping("/api/action/health/readiness")
    public Mono<Map<String, Object>> readiness() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", serviceName);
        status.put("uptime", Duration.between(startTime, Instant.now()).toSeconds());
        return Mono.just(status);
    }

    /**
     * 综合健康检查端点。
     *
     * <p>返回服务状态、名称、traceId 和运行时长。traceId 每次请求重新生成，
     * 可用于日志关联。</p>
     *
     * @return 包含完整健康信息的 Map
     */
    @GetMapping("/api/action/health")
    public Mono<Map<String, Object>> health() {
        // 生成去连字符的 UUID 作为 traceId
        String traceId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", serviceName);
        status.put("traceId", traceId);
        status.put("uptimeSeconds", Duration.between(startTime, Instant.now()).toSeconds());
        return Mono.just(status);
    }
}
