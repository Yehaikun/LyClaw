package lyjew.com.lyclaw.web.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Tag(name = "Health", description = "健康检查接口")
@RestController
public class HealthController {

    private final String serviceName = "lyclaw-web-service";
    private final Instant startTime = Instant.now();

    @Operation(summary = "存活性检查", description = "Kubernetes liveness probe，仅检查服务是否存活")
    @GetMapping("/api/web/health/liveness")
    public Mono<Map<String, Object>> liveness() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", serviceName);
        return Mono.just(status);
    }

    @Operation(summary = "就绪性检查", description = "Kubernetes readiness probe，检查服务是否就绪接收流量")
    @GetMapping("/api/web/health/readiness")
    public Mono<Map<String, Object>> readiness() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", serviceName);
        status.put("uptime", Duration.between(startTime, Instant.now()).toSeconds());
        return Mono.just(status);
    }

    @Operation(summary = "综合健康检查", description = "返回服务状态、运行时间和追踪ID")
    @GetMapping("/api/web/health")
    public Mono<Map<String, Object>> health() {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", serviceName);
        status.put("traceId", traceId);
        status.put("uptimeSeconds", Duration.between(startTime, Instant.now()).toSeconds());
        return Mono.just(status);
    }
}
