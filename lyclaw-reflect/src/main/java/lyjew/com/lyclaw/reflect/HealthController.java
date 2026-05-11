package lyjew.com.lyclaw.reflect;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 反思服务健康检查控制器。
 *
 * <p>为Kubernetes探针和监控系统提供响应式健康检查端点。使用WebFlux的 {@link Mono} 进行异步响应，
 * 返回JSON格式的服务状态信息。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>使用 {@link LinkedHashMap} 保证响应JSON字段顺序一致</li>
 *   <li>通过 {@link Duration#between} 计算精确的运行时长</li>
 *   <li>每次健康检查返回唯一traceId，便于日志追踪</li>
 * </ul>
 */
@RestController("reflectHealthController")
public class HealthController {

    /** 服务名称标识，用于健康检查响应 */
    private final String serviceName;
    /** 服务启动时间，用于计算运行时长 */
    private final Instant startTime = Instant.now();

    /**
     * 构造健康控制器，初始化服务名称。
     */
    public HealthController() {
        this.serviceName = "reflect-service";
    }

    /**
     * 存活探针（Liveness Probe）。
     *
     * <p>仅返回服务是否存活，不做任何外部依赖检查。
     * Kubernetes根据此端点决定是否需要重启Pod。</p>
     *
     * @return 包含status和service名称的Mono响应
     */
    @GetMapping("/api/reflect/health/liveness")
    public Mono<Map<String, Object>> liveness() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", serviceName);
        return Mono.just(status);
    }

    /**
     * 就绪探针（Readiness Probe）。
     *
     * <p>返回服务是否就绪（含运行时长）。Kubernetes根据此端点决定是否将流量路由到该Pod。</p>
     *
     * @return 包含status、service名称和运行秒数的Mono响应
     */
    @GetMapping("/api/reflect/health/readiness")
    public Mono<Map<String, Object>> readiness() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", serviceName);
        // 计算从启动到当前时刻的间隔秒数
        status.put("uptime", Duration.between(startTime, Instant.now()).toSeconds());
        return Mono.just(status);
    }

    /**
     * 综合健康检查端点。
     *
     * <p>返回完整的健康状态信息，包括服务状态、运行时长和追踪ID。
     * 可用于监控仪表盘聚合展示。</p>
     *
     * @return 包含完整健康状态信息的Mono响应
     */
    @GetMapping("/api/reflect/health")
    public Mono<Map<String, Object>> health() {
        // 生成无连字符的唯一追踪ID
        String traceId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", serviceName);
        status.put("traceId", traceId);
        status.put("uptimeSeconds", Duration.between(startTime, Instant.now()).toSeconds());
        return Mono.just(status);
    }
}
