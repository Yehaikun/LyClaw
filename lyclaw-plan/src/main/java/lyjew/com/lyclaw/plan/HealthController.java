package lyjew.com.lyclaw.plan;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController("planHealthController")
public class HealthController {

    private final String serviceName;
    private final Instant startTime = Instant.now();

    public HealthController() {
        this.serviceName = "plan-service";
    }

    @GetMapping("/api/plan/health/liveness")
    public Mono<Map<String, Object>> liveness() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", serviceName);
        return Mono.just(status);
    }

    @GetMapping("/api/plan/health/readiness")
    public Mono<Map<String, Object>> readiness() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", serviceName);
        status.put("uptime", Duration.between(startTime, Instant.now()).toSeconds());
        return Mono.just(status);
    }

    @GetMapping("/api/plan/health")
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
