package lyjew.com.lyclaw.orchestration.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.agent.*;
import lyjew.com.lyclaw.dto.AgentResult;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class AgentLifecycleManager implements AgentLifecycle {

    private final ConcurrentHashMap<String, AgentState> stateMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AgentHandle> handleMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AgentSpec> specMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<AgentResult>> futureMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> startTimeMap = new ConcurrentHashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(0);
    private final ExecutorService agentExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "lifecycle-agent-worker");
        t.setDaemon(true);
        return t;
    });

    @Override
    public CompletableFuture<AgentHandle> create(AgentSpec spec) {
        String agentId = "agent-" + idCounter.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);

        AgentHandle handle = AgentHandle.builder()
                .agentId(agentId)
                .name(spec.getName())
                .state(AgentState.IDLE)
                .capabilities(spec.getCapabilities() != null
                        ? List.copyOf(spec.getCapabilities()) : Collections.emptyList())
                .createdAt(System.currentTimeMillis())
                .historicalAccuracy(0.8)
                .build();

        stateMap.put(agentId, AgentState.IDLE);
        handleMap.put(agentId, handle);
        specMap.put(agentId, spec);

        log.info("[LifecycleManager] Agent created: agentId={}, name={}, capabilities={}",
                agentId, spec.getName(), spec.getCapabilities());

        return CompletableFuture.completedFuture(handle);
    }

    @Override
    public CompletableFuture<AgentResult> schedule(String agentId, AgentTask task) {
        AgentState currentState = stateMap.get(agentId);
        if (currentState == null) {
            log.warn("[LifecycleManager] Cannot schedule unknown agent: {}", agentId);
            return CompletableFuture.completedFuture(
                    new AgentResult(agentId, "FAILED",
                            "Agent not found: " + agentId, "", 0));
        }

        if (currentState != AgentState.IDLE) {
            log.warn("[LifecycleManager] Cannot schedule agent {} in state {}", agentId, currentState);
            return CompletableFuture.completedFuture(
                    new AgentResult(agentId, "FAILED",
                            "Agent is not IDLE (current: " + currentState + ")", "", 0));
        }

        if (!stateMap.replace(agentId, AgentState.IDLE, AgentState.RUNNING)) {
            log.warn("[LifecycleManager] Race condition: agent {} state changed concurrently", agentId);
            return CompletableFuture.completedFuture(
                    new AgentResult(agentId, "FAILED",
                            "State changed concurrently, abort scheduling", "", 0));
        }

        startTimeMap.put(agentId, System.nanoTime());

        log.info("[LifecycleManager] Scheduling task on agent {}: taskId={}, type={}",
                agentId, task.getTaskId(), task.getType());

        CompletableFuture<AgentResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                long start = System.currentTimeMillis();
                log.info("[LifecycleManager] Agent {} executing task: {}", agentId, task.getTaskId());

                String resultPayload = "Task " + task.getTaskId() + " completed by " + agentId
                        + " (type=" + task.getType() + ", target=" + task.getTarget() + ")";
                long elapsed = System.currentTimeMillis() - start;

                stateMap.put(agentId, AgentState.COMPLETED);

                AgentResult result = new AgentResult(agentId, "COMPLETED",
                        "Task executed successfully",
                        resultPayload, elapsed);

                log.info("[LifecycleManager] Agent {} completed task {} in {}ms",
                        agentId, task.getTaskId(), elapsed);

                AgentHandle handle = handleMap.get(agentId);
                if (handle != null) {
                    handle.setHistoricalAccuracy(
                            Math.min(1.0, handle.getHistoricalAccuracy() * 0.95 + 0.05));
                }

                return result;
            } catch (Exception e) {
                stateMap.put(agentId, AgentState.FAILED);
                long elapsed = startTimeMap.containsKey(agentId)
                        ? (System.nanoTime() - startTimeMap.get(agentId)) / 1_000_000 : 0;

                log.error("[LifecycleManager] Agent {} failed: {}", agentId, e.getMessage(), e);
                return new AgentResult(agentId, "FAILED",
                        "Execution failed: " + e.getMessage(),
                        e.toString(), elapsed);
            }
        }, agentExecutor);

        futureMap.put(agentId, future);

        return future;
    }

    @Override
    public boolean pause(String agentId) {
        AgentState current = stateMap.get(agentId);
        if (current == null) {
            log.warn("[LifecycleManager] Cannot pause unknown agent: {}", agentId);
            return false;
        }

        if (current != AgentState.RUNNING && current != AgentState.WAITING) {
            log.warn("[LifecycleManager] Cannot pause agent {} in state {}", agentId, current);
            return false;
        }

        stateMap.put(agentId, AgentState.IDLE);
        log.info("[LifecycleManager] Agent {} paused (was {})", agentId, current);

        AgentHandle handle = handleMap.get(agentId);
        if (handle != null) {
            handle.setState(AgentState.IDLE);
        }

        return true;
    }

    @Override
    public boolean resume(String agentId) {
        AgentState current = stateMap.get(agentId);
        if (current == null) {
            log.warn("[LifecycleManager] Cannot resume unknown agent: {}", agentId);
            return false;
        }

        if (current != AgentState.IDLE) {
            log.warn("[LifecycleManager] Cannot resume agent {} in state {}", agentId, current);
            return false;
        }

        stateMap.put(agentId, AgentState.RUNNING);
        log.info("[LifecycleManager] Agent {} resumed", agentId);

        AgentHandle handle = handleMap.get(agentId);
        if (handle != null) {
            handle.setState(AgentState.RUNNING);
        }

        return true;
    }

    @Override
    public boolean terminate(String agentId) {
        AgentState current = stateMap.get(agentId);
        if (current == null) {
            log.warn("[LifecycleManager] Cannot terminate unknown agent: {}", agentId);
            return false;
        }

        CompletableFuture<AgentResult> future = futureMap.remove(agentId);
        if (future != null && !future.isDone()) {
            future.cancel(true);
            log.info("[LifecycleManager] Agent {} future cancelled", agentId);
        }

        stateMap.put(agentId, AgentState.CANCELLED);
        startTimeMap.remove(agentId);

        AgentHandle handle = handleMap.get(agentId);
        if (handle != null) {
            handle.setState(AgentState.CANCELLED);
        }

        log.info("[LifecycleManager] Agent {} terminated (was {})", agentId, current);
        return true;
    }

    @Override
    public AgentState getState(String agentId) {
        return stateMap.get(agentId);
    }

    public AgentHandle getHandle(String agentId) {
        return handleMap.get(agentId);
    }

    public ConcurrentHashMap<String, AgentState> getAllStates() {
        return new ConcurrentHashMap<>(stateMap);
    }

    public long countByState(AgentState state) {
        return stateMap.values().stream().filter(s -> s == state).count();
    }

    public long getIdleCount() {
        return countByState(AgentState.IDLE);
    }

    public long getRunningCount() {
        return countByState(AgentState.RUNNING);
    }
}
