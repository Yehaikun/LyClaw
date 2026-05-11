package lyjew.com.lyclaw.protocol;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 协议服务的健康检查控制器，提供Kubernetes风格的存活性(liveness)和就绪性(readiness)端点。
 */
@RestController("protocolHealthController")
public class HealthController {

    /** 服务名称标识，用于健康检查响应 */
    private final String serviceName;
    /** 服务启动时间，用于计算运行时长 */
    private final Instant startTime = Instant.now();

    /**
     * 构造健康控制器，初始化服务名称。
     */
    public HealthController() {
        this.serviceName = "protocol-service";
    }

    /**
     * 存活性检查：用于判断服务进程是否仍在运行。
     *
     * @return 始终返回UP状态
     */
    @GetMapping("/api/protocol/health/liveness")
    public Mono<Map<String, Object>> liveness() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", serviceName);
        return Mono.just(status);
    }

    /**
     * 就绪性检查：用于判断服务是否准备好接收流量。
     *
     * @return 包含状态和运行时间的响应
     */
    @GetMapping("/api/protocol/health/readiness")
    public Mono<Map<String, Object>> readiness() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", serviceName);
        status.put("uptime", Duration.between(startTime, Instant.now()).toSeconds());
        return Mono.just(status);
    }

    /**
     * 综合健康检查：返回服务状态、traceId和运行时长。
     *
     * @return 包含完整健康信息的响应
     */
    @GetMapping("/api/protocol/health")
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
