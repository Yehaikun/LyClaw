package lyjew.com.lyclaw.action.agent;

import lyjew.com.lyclaw.agent.AgentCollaborationMode;
import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.AgentHandle.HealthStatus;
import lyjew.com.lyclaw.agent.AgentLifecycle;
import lyjew.com.lyclaw.agent.AgentSpec;
import lyjew.com.lyclaw.agent.AgentState;
import lyjew.com.lyclaw.agent.AgentTask;
import lyjew.com.lyclaw.dto.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentLifecycleImpl implements AgentLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AgentLifecycleImpl.class);

    private final DefaultAgentRegistry registry;

    public AgentLifecycleImpl(DefaultAgentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public CompletableFuture<AgentHandle> create(AgentSpec spec) {
        return CompletableFuture.supplyAsync(() -> {
            String agentId = spec.getName().toLowerCase().replaceAll("[^a-z0-9-]", "-");
            if (registry.lookup(agentId).isPresent()) {
                agentId = agentId + "-" + UUID.randomUUID().toString().substring(0, 8);
            }

            String modelName = spec.getModelName() != null ? spec.getModelName() : "deepseek-chat";
            @SuppressWarnings("unchecked")
            Map<String, String> extMap = spec.getConfig() != null
                    ? (Map<String, String>) (Map<?, ?>) spec.getConfig()
                    : Collections.emptyMap();

            AgentHandle handle = AgentHandle.builder()
                    .agentId(agentId)
                    .name(spec.getName() != null ? spec.getName() : agentId)
                    .description(spec.getDescription() != null ? spec.getDescription() : "")
                    .state(AgentState.IDLE)
                    .health(HealthStatus.UP)
                    .capabilities(spec.getCapabilities() != null ? spec.getCapabilities() : Collections.emptyList())
                    .model(modelName)
                    .provider("deepseek")
                    .systemPrompt("")
                    .collaborationMode(AgentCollaborationMode.WORKER)
                    .allowAgents(Collections.emptyList())
                    .maxSpawnDepth(1)
                    .maxChildrenPerAgent(5)
                    .extensions(extMap)
                    .activeSubagentCount(0)
                    .totalTasksCompleted(0)
                    .totalTasksFailed(0)
                    .historicalAccuracy(1.0)
                    .createdAt(LocalDateTime.now())
                    .lastActiveAt(LocalDateTime.now())
                    .build();

            registry.register(handle);
            log.info("Agent created: {} ({}) via AgentSpec", agentId, spec.getName());
            return handle;
        });
    }

    @Override
    public CompletableFuture<AgentResult> schedule(String agentId, AgentTask task) {
        return CompletableFuture.supplyAsync(() -> {
            AgentHandle handle = registry.lookup(agentId).orElse(null);
            if (handle == null) {
                return new AgentResult(agentId, "FAILED", "Agent not found",
                        "No agent registered with id: " + agentId, 0);
            }
            if (handle.getState() != AgentState.IDLE) {
                return new AgentResult(agentId, "FAILED", "Agent not idle",
                        "Agent " + agentId + " is in state " + handle.getState(), 0);
            }

            registry.updateState(agentId, AgentState.RUNNING);
            long startMs = System.currentTimeMillis();

            try {
                // 第 2 阶段之后，这里会通过 Orchestrator + SubagentSpawner 将任务委派给子 Agent
                // 当前 Phase 1 仅做状态管理和记录
                String result = executeTask(task);

                long elapsed = System.currentTimeMillis() - startMs;
                registry.updateState(agentId, AgentState.COMPLETED);
                handle.setTotalTasksCompleted(handle.getTotalTasksCompleted() + 1);
                handle.setLastActiveAt(LocalDateTime.now());
                registry.recordHeartbeat(agentId);

                return new AgentResult(agentId, "COMPLETED", "Task completed", result, elapsed);

            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - startMs;
                registry.updateState(agentId, AgentState.FAILED);
                handle.setTotalTasksFailed(handle.getTotalTasksFailed() + 1);
                handle.setLastActiveAt(LocalDateTime.now());
                log.error("Task execution failed for agent {}: {}", agentId, e.getMessage());
                return new AgentResult(agentId, "FAILED", "Task failed: " + e.getMessage(), e.toString(), elapsed);
            }
        });
    }

    @Override
    public boolean pause(String agentId) {
        AgentHandle handle = registry.lookup(agentId).orElse(null);
        if (handle == null || handle.getState() == AgentState.PAUSED) return false;
        registry.updateState(agentId, AgentState.PAUSED);
        log.info("Agent paused: {}", agentId);
        return true;
    }

    @Override
    public boolean resume(String agentId) {
        AgentHandle handle = registry.lookup(agentId).orElse(null);
        if (handle == null || handle.getState() != AgentState.PAUSED) return false;
        registry.updateState(agentId, AgentState.IDLE);
        log.info("Agent resumed: {}", agentId);
        return true;
    }

    @Override
    public boolean terminate(String agentId) {
        AgentHandle handle = registry.lookup(agentId).orElse(null);
        if (handle == null) return false;
        registry.updateState(agentId, AgentState.CANCELLED);
        registry.unregister(agentId);
        log.info("Agent terminated: {}", agentId);
        return true;
    }

    @Override
    public AgentState getState(String agentId) {
        return registry.lookup(agentId).map(AgentHandle::getState).orElse(null);
    }

    private String executeTask(AgentTask task) {
        // Phase 1: 占位实现 — 记录任务信息
        // Phase 2 之后会路由到实际执行引擎
        return "[Phase 1 placeholder] Task received: type=" + task.getType()
                + ", payload=" + (task.getPayload() != null ? task.getPayload() : "none");
    }
}
