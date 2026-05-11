package lyjew.com.lyclaw.orchestration.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 编排服务健康检查控制器。
 *
 * 提供 Kubernetes 探针所需的三类端点：
 * liveness（存活）、readiness（就绪）和综合 health 检查。
 * 所有端点返回响应式 Mono，与 WebFlux 响应式栈保持一致。
 */
@RestController("orchestrationHealthController")  // 指定 Bean 名称，避免与其他模块同名冲突
public class HealthController {

    private final String serviceName = "orchestration-service";
    private final Instant startTime = Instant.now();  // 记录服务启动时间，用于计算 uptime

    /**
     * 存活探针 —— 仅返回服务是否存活，不做深度依赖检查。
     *
     * @return 包含 status 和 service 的 Map
     */
    @GetMapping("/api/orchestration/health/liveness")
    public Mono<Map<String, Object>> liveness() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", serviceName);
        return Mono.just(status);
    }

    /**
     * 就绪探针 —— 返回服务就绪状态及已运行秒数。
     *
     * @return 包含 status、service 和 uptime 的 Map
     */
    @GetMapping("/api/orchestration/health/readiness")
    public Mono<Map<String, Object>> readiness() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", serviceName);
        status.put("uptime", Duration.between(startTime, Instant.now()).toSeconds());
        return Mono.just(status);
    }

    /**
     * 综合健康检查 —— 返回服务状态、运行时长和追踪 ID。
     *
     * @return 包含 status、service、traceId 和 uptimeSeconds 的 Map
     */
    @GetMapping("/api/orchestration/health")
    public Mono<Map<String, Object>> health() {
        // 生成无连字符的 UUID 作为追踪 ID
        String traceId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", serviceName);
        status.put("traceId", traceId);
        status.put("uptimeSeconds", Duration.between(startTime, Instant.now()).toSeconds());
        return Mono.just(status);
    }
}
