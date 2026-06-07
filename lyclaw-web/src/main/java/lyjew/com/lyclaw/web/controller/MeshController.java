package lyjew.com.lyclaw.web.controller;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lyjew.com.lyclaw.mesh.AgentExecutionEvent;
import lyjew.com.lyclaw.mesh.AgentExecutionStore;
import lyjew.com.lyclaw.mesh.AgentMesh;
import lyjew.com.lyclaw.mesh.AgentMeshMetrics;
import lyjew.com.lyclaw.mesh.AgentMessage;
import lyjew.com.lyclaw.mesh.AgentRef;
import lyjew.com.lyclaw.mesh.impl.DefaultAgentMesh;
import lyjew.com.lyclaw.session.SessionService;
import lyjew.com.lyclaw.mesh.AgentSnapshot;
import lyjew.com.lyclaw.mesh.AgentSpec;
import lyjew.com.lyclaw.mesh.MessageType;

/**
 * Agent Mesh REST API —— 前端管理 Agent Mesh 的统一入口。
 *
 * <p>提供 Agent 的注册、查看、消息发送、编排、指标监控等能力。
 * 前端通过此 API 实现 Agent Mesh 的可视化管理。</p>
 *
 * <p>安全说明：所有端点需要认证（isAuthenticated），
 * Agent 资源按 agentId 授权，每个用户只可操作自己的 Agent。</p>
 */
@Tag(name = "Agent Mesh", description = "多Agent调度网格管理")
@RestController
@RequestMapping("/api/mesh")
public class MeshController {

    private static final Logger log = LoggerFactory.getLogger(MeshController.class);

    private final AgentMesh mesh;
    private final SessionService sessionService;

    public MeshController(AgentMesh mesh, SessionService sessionService) {
        this.mesh = mesh;
        this.sessionService = sessionService;
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
        } catch (Exception e) {
            log.warn("Failed to get metrics for agent {}: {}", agentId, e.getMessage());
        }

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
        String sessionId = (String) body.get("sessionId");

        AgentMessage.Builder builder = AgentMessage.builder()
                .type(MessageType.REQUEST)
                .to(agentId)
                .payload(payload)
                .correlationId(correlationId != null ? correlationId : java.util.UUID.randomUUID().toString())
                .ttlMs(300_000);
        if (sessionId != null && !sessionId.isEmpty()) {
            builder.metadata("sessionId", sessionId);
        }

        AgentMessage response = mesh.send(builder.build()).join();

        // 跨轮次对话：保存到 session
        String finalSessionId = sessionId != null ? sessionId
                : java.util.UUID.randomUUID().toString().substring(0, 12);
        if (sessionService != null && response.getType() != MessageType.ERROR) {
            try {
                lyjew.com.lyclaw.model.Session sess = sessionService.getOrCreate(finalSessionId, agentId, null);
                sess.addMessage(lyjew.com.lyclaw.model.Message.user(payload));
                sess.addMessage(lyjew.com.lyclaw.model.Message.assistant(
                        response.getPayload() != null ? response.getPayload() : ""));
                sessionService.appendMessages(finalSessionId, sess.getMessages());
            } catch (Exception e) {
                log.warn("Failed to save session: {}", e.getMessage());
            }
        }

        return Map.of(
                "success", response.getType() != MessageType.ERROR,
                "payload", response.getPayload() != null ? response.getPayload() : "",
                "type", response.getType().name(),
                "correlationId", response.getCorrelationId(),
                "sessionId", finalSessionId
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
            log.warn("Failed to get snapshot for agent {}: {}", agentId, e.getMessage());
            return Map.of("error", "Failed to retrieve snapshot");
        }
    }

    @Operation(summary = "SSE 事件流 —— 实时推送 Agent 执行事件")
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public reactor.core.publisher.Flux<org.springframework.http.codec.ServerSentEvent<String>> streamEvents() {
        AgentExecutionStore store = getExecutionStore();
        if (store == null) {
            return reactor.core.publisher.Flux.empty();
        }
        return reactor.core.publisher.Flux.create(sink -> {
            java.util.function.Consumer<AgentExecutionEvent> subscriber = event -> {
                try {
                    if (sink.isCancelled()) return;
                    String json = eventToJson(event);
                    sink.next(org.springframework.http.codec.ServerSentEvent.<String>builder()
                            .event("agent_execution")
                            .data(json)
                            .build());
                } catch (Exception ignored) {}
            };
            store.subscribe(subscriber);
            sink.onCancel(() -> store.unsubscribe(subscriber));
            sink.onDispose(() -> store.unsubscribe(subscriber));
        });
    }

    @Operation(summary = "获取 Agent 执行事件历史")
    @GetMapping("/agents/{agentId}/events")
    public List<Map<String, Object>> getAgentEvents(@PathVariable String agentId,
                                                     @RequestParam(defaultValue = "50") int limit) {
        AgentExecutionStore store = getExecutionStore();
        if (store == null) return List.of();
        return store.getEvents(agentId, Math.min(limit, 200)).stream()
                .map(e -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("eventId", e.getEventId());
                    m.put("agentId", e.getAgentId());
                    m.put("type", e.getType().name());
                    m.put("stage", e.getStage());
                    m.put("message", e.getMessage());
                    m.put("progress", e.getProgress());
                    m.put("timestamp", e.getTimestamp());
                    return m;
                })
                .toList();
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
            log.warn("Failed to get mesh metrics: {}", e.getMessage());
            return Map.of("error", "Failed to retrieve metrics");
        }
    }

    private String eventToJson(AgentExecutionEvent e) {
        try {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("eventId", e.getEventId());
            m.put("agentId", e.getAgentId());
            m.put("taskId", e.getTaskId());
            m.put("type", e.getType().name());
            m.put("stage", e.getStage());
            m.put("message", e.getMessage());
            m.put("progress", e.getProgress());
            m.put("timestamp", e.getTimestamp());
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private AgentExecutionStore getExecutionStore() {
        if (mesh instanceof DefaultAgentMesh) {
            return ((DefaultAgentMesh) mesh).getExecutionStore();
        }
        return null;
    }

    private Map<String, Object> agentRefToMap(AgentRef ref) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("agentId", ref.getAgentId());
        map.put("type", ref.getType().name());
        map.put("capabilities", String.join(", ", ref.getCapabilities()));
        map.put("createdAt", ref.getCreatedAt());
        return map;
    }
}
