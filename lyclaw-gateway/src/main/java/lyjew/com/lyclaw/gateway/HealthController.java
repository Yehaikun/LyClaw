package lyjew.com.lyclaw.gateway;

import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
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

    public HealthController(DiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
    }

    @GetMapping("/api/dashboard/health")
    public Mono<Map<String, Object>> health() {
        Map<String, Object> result = new LinkedHashMap<>();
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
}
