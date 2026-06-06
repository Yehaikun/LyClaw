package lyjew.com.lyclaw.web.controller;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lyjew.com.lyclaw.mesh.AgentMesh;
import lyjew.com.lyclaw.mesh.AgentMeshMetrics;
import lyjew.com.lyclaw.mesh.AgentMessage;
import lyjew.com.lyclaw.mesh.AgentRef;
import lyjew.com.lyclaw.mesh.AgentSnapshot;
import lyjew.com.lyclaw.mesh.AgentSpec;
import lyjew.com.lyclaw.mesh.MessageType;
import lyjew.com.lyclaw.mesh.OrchestrationEngine;
import lyjew.com.lyclaw.mesh.OrchestrationPattern;
import lyjew.com.lyclaw.mesh.OrchestrationResult;
import lyjew.com.lyclaw.mesh.OrchestrationSpec;

/**
 * Agent Mesh REST API —— 前端管理 Agent Mesh 的统一入口。
 *
 * <p>提供 Agent 的注册、查看、消息发送、编排、指标监控等能力。
 * 前端通过此 API 实现 Agent Mesh 的可视化管理。</p>
 */
@Tag(name = "Agent Mesh", description = "多Agent调度网格管理")
@RestController
@RequestMapping("/api/mesh")
public class MeshController {

    private final AgentMesh mesh;
    private final OrchestrationEngine orchestrationEngine;

    public MeshController(AgentMesh mesh, OrchestrationEngine orchestrationEngine) {
        this.mesh = mesh;
        this.orchestrationEngine = orchestrationEngine;
    }

    @Operation(summary = "列出所有 Agent")
    @GetMapping("/agents")
    public List<Map<String, Object>> listAgents() {
        return mesh.getAllAgents().stream()
                .map(this::agentRefToMap)
                .collect(Collectors.toList());
    }

    @Operation(summary = "获取 Agent 详情")
    @GetMapping("/agents/{agentId}")
    public Map<String, Object> getAgent(@PathVariable String agentId) {
        AgentRef ref = mesh.lookup(agentId)
                .orElseThrow(() -> new RuntimeException("Agent not found: " + agentId));

        Map<String, Object> info = agentRefToMap(ref);

        // 追加运行时状态
        mesh.getInstance(agentId).ifPresent(instance -> {
            info.put("state", instance.getState().name());
            info.put("type", instance.getType().name());
            info.put("totalCalls", instance.getHandle().getTotalRequestsHandled());
            info.put("totalErrors", instance.getHandle().getTotalErrors());
            info.put("activeRequests", instance.getHandle().getActiveRequestCount());
            info.put("health", instance.getHandle().getHealth().name());
        });

        // 追加指标
        try {
            java.lang.reflect.Method getMetrics = mesh.getClass().getMethod("getMetrics");
            AgentMeshMetrics metrics = (AgentMeshMetrics) getMetrics.invoke(mesh);
            AgentMeshMetrics.AgentMetrics agentMetrics = metrics.getAgentMetrics(agentId);
            info.put("avgDurationMs", Math.round(agentMetrics.avgDurationMs()));
            info.put("successRate", Math.round(agentMetrics.successRate()));
        } catch (Exception ignored) {}

        return info;
    }

    @Operation(summary = "注册新 Agent")
    @PostMapping("/agents")
    public Map<String, Object> registerAgent(@RequestBody Map<String, Object> body) {
        String agentId = (String) body.getOrDefault("agentId", "");
        if (agentId.isBlank()) {
            throw new RuntimeException("agentId is required");
        }

        @SuppressWarnings("unchecked")
        List<String> capabilities = (List<String>) body.get("capabilities");

        AgentSpec spec = AgentSpec.builder()
                .agentId(agentId)
                .name((String) body.getOrDefault("name", agentId))
                .description((String) body.get("description"))
                .capabilities(capabilities)
                .model((String) body.get("model"))
                .systemPrompt((String) body.get("systemPrompt"))
                .build();

        AgentRef ref = mesh.register(spec);
        return Map.of(
                "agentId", ref.getAgentId(),
                "type", ref.getType().name(),
                "capabilities", String.join(", ", ref.getCapabilities()),
                "status", "registered"
        );
    }

    @Operation(summary = "注销 Agent")
    @DeleteMapping("/agents/{agentId}")
    public Map<String, Object> unregisterAgent(@PathVariable String agentId) {
        mesh.unregister(agentId);
        return Map.of("agentId", agentId, "status", "unregistered");
    }

    @Operation(summary = "向 Agent 发送消息")
    @PostMapping("/agents/{agentId}/send")
    public Map<String, Object> sendMessage(@PathVariable String agentId,
                                            @RequestBody Map<String, Object> body) {
        String payload = (String) body.getOrDefault("payload", "");
        String correlationId = (String) body.get("correlationId");

        AgentMessage request = AgentMessage.builder()
                .type(MessageType.REQUEST)
                .to(agentId)
                .payload(payload)
                .correlationId(correlationId != null ? correlationId : java.util.UUID.randomUUID().toString())
                .ttlMs(300_000)
                .build();

        AgentMessage response = mesh.send(request).join();

        return Map.of(
                "success", response.getType() != MessageType.ERROR,
                "payload", response.getPayload() != null ? response.getPayload() : "",
                "type", response.getType().name(),
                "correlationId", response.getCorrelationId()
        );
    }

    @Operation(summary = "获取 Agent 快照")
    @GetMapping("/agents/{agentId}/snapshot")
    public Map<String, Object> getSnapshot(@PathVariable String agentId) {
        try {
            java.lang.reflect.Method snapshotMethod = mesh.getClass().getMethod("snapshot", String.class);
            AgentSnapshot snapshot = (AgentSnapshot) snapshotMethod.invoke(mesh, agentId);
            if (snapshot == null) {
                return Map.of("error", "Agent not found: " + agentId);
            }
            return Map.of(
                    "agentId", snapshot.getAgentId(),
                    "state", snapshot.getState().name(),
                    "type", snapshot.getType().name(),
                    "totalCalls", snapshot.getTotalCalls(),
                    "totalErrors", snapshot.getTotalErrors(),
                    "callHistory", snapshot.formatCallHistory()
            );
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    @Operation(summary = "执行编排")
    @PostMapping("/orchestrate")
    public Map<String, Object> orchestrate(@RequestBody Map<String, Object> body) {
        String pattern = (String) body.getOrDefault("pattern", "SINGLE");
        String task = (String) body.getOrDefault("task", "");

        @SuppressWarnings("unchecked")
        List<String> agentIds = (List<String>) body.get("agentIds");

        @SuppressWarnings("unchecked")
        List<String> capabilities = (List<String>) body.get("capabilities");

        OrchestrationSpec spec = OrchestrationSpec.builder()
                .pattern(OrchestrationPattern.valueOf(pattern.toUpperCase()))
                .task(task)
                .agentIds(agentIds)
                .capabilities(capabilities)
                .aggregationStrategy((String) body.getOrDefault("aggregationStrategy", "sum"))
                .timeoutMs(300_000)
                .build();

        OrchestrationResult result = orchestrationEngine.execute(spec);

        return Map.of(
                "success", result.isSuccess(),
                "pattern", result.getPattern().name(),
                "result", result.getResult() != null ? result.getResult() : "",
                "error", result.getError() != null ? result.getError() : "",
                "agentResults", result.getAgentResults().stream().map(r -> Map.of(
                        "agentId", r.getAgentId(),
                        "success", r.isSuccess(),
                        "resultPreview", r.getResult() != null
                                ? r.getResult().substring(0, Math.min(100, r.getResult().length())) : ""
                )).toList(),
                "durationMs", result.getDurationMs()
        );
    }

    @Operation(summary = "获取 Mesh 指标")
    @GetMapping("/metrics")
    public Map<String, Object> getMetrics() {
        try {
            java.lang.reflect.Method getMetrics = mesh.getClass().getMethod("getMetrics");
            AgentMeshMetrics metrics = (AgentMeshMetrics) getMetrics.invoke(mesh);
            AgentMeshMetrics.MetricsSnapshot snapshot = metrics.snapshot();

            return Map.of(
                    "totalCalls", snapshot.totalCalls,
                    "totalErrors", snapshot.totalErrors,
                    "totalDurationMs", snapshot.totalDurationMs,
                    "agentCount", snapshot.agentCount,
                    "agents", snapshot.agents.entrySet().stream().collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> Map.of(
                                    "totalCalls", e.getValue().totalCalls.get(),
                                    "successRate", Math.round(e.getValue().successRate()),
                                    "avgDurationMs", Math.round(e.getValue().avgDurationMs()),
                                    "activeRequests", e.getValue().activeRequests.get()
                            )
                    ))
            );
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    private Map<String, Object> agentRefToMap(AgentRef ref) {
        return Map.of(
                "agentId", ref.getAgentId(),
                "type", ref.getType().name(),
                "capabilities", String.join(", ", ref.getCapabilities()),
                "createdAt", ref.getCreatedAt()
        );
    }
}
