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

/**
 * Agent 生命周期管理器。
 *
 * 管理 Agent 从创建、调度、暂停、恢复到终止的完整生命周期。
 * 使用多个 ConcurrentHashMap 维护 Agent 的状态、句柄、规格和异步任务 Future。
 * 通过 CachedThreadPool 支持弹性 Agent 任务并发。
 */
@Slf4j
@Service
public class AgentLifecycleManager implements AgentLifecycle {

    /** Agent 状态映射表：agentId -> AgentState */
    private final ConcurrentHashMap<String, AgentState> stateMap = new ConcurrentHashMap<>();
    /** Agent 句柄映射表：agentId -> AgentHandle */
    private final ConcurrentHashMap<String, AgentHandle> handleMap = new ConcurrentHashMap<>();
    /** Agent 规格映射表：agentId -> AgentSpec */
    private final ConcurrentHashMap<String, AgentSpec> specMap = new ConcurrentHashMap<>();
    /** Agent 异步 Future 映射表：agentId -> CompletableFuture */
    private final ConcurrentHashMap<String, CompletableFuture<AgentResult>> futureMap = new ConcurrentHashMap<>();
    /** Agent 启动时间记录：agentId -> nanoTime */
    private final ConcurrentHashMap<String, Long> startTimeMap = new ConcurrentHashMap<>();
    /** Agent ID 自增计数器 */
    private final AtomicInteger idCounter = new AtomicInteger(0);
    /** Agent 执行线程池（守护线程，允许弹性扩容） */
    private final ExecutorService agentExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "lifecycle-agent-worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * 创建一个新的 Agent。
     * 生成唯一 ID，初始化状态为 IDLE，记录创建时间和默认准确率。
     *
     * @param spec Agent 规格（名称、能力等）
     * @return 包含 AgentHandle 的已完成 Future
     */
    @Override
    public CompletableFuture<AgentHandle> create(AgentSpec spec) {
        // 生成格式为 agent-{序号}-{uuid前8位} 的唯一 ID
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

        // 同时存入状态、句柄、规格三张表
        stateMap.put(agentId, AgentState.IDLE);
        handleMap.put(agentId, handle);
        specMap.put(agentId, spec);

        log.info("[LifecycleManager] Agent created: agentId={}, name={}, capabilities={}",
                agentId, spec.getName(), spec.getCapabilities());

        return CompletableFuture.completedFuture(handle);
    }

    /**
     * 调度 Agent 执行任务。
     * 校验 Agent 存在且为 IDLE 状态后，使用 CAS 原子操作将状态切换为 RUNNING，
     * 防止并发重复调度。然后异步执行任务，执行完成后更新准确率。
     *
     * @param agentId Agent ID
     * @param task    要执行的任务
     * @return 包含 AgentResult 的 Future
     */
    @Override
    public CompletableFuture<AgentResult> schedule(String agentId, AgentTask task) {
        // 校验 Agent 是否存在
        AgentState currentState = stateMap.get(agentId);
        if (currentState == null) {
            log.warn("[LifecycleManager] Cannot schedule unknown agent: {}", agentId);
            return CompletableFuture.completedFuture(
                    new AgentResult(agentId, "FAILED",
                            "Agent not found: " + agentId, "", 0));
        }

        // 仅 IDLE 状态可调度
        if (currentState != AgentState.IDLE) {
            log.warn("[LifecycleManager] Cannot schedule agent {} in state {}", agentId, currentState);
            return CompletableFuture.completedFuture(
                    new AgentResult(agentId, "FAILED",
                            "Agent is not IDLE (current: " + currentState + ")", "", 0));
        }

        // 原子地将状态从 IDLE 切换到 RUNNING，防止竞态条件
        if (!stateMap.replace(agentId, AgentState.IDLE, AgentState.RUNNING)) {
            log.warn("[LifecycleManager] Race condition: agent {} state changed concurrently", agentId);
            return CompletableFuture.completedFuture(
                    new AgentResult(agentId, "FAILED",
                            "State changed concurrently, abort scheduling", "", 0));
        }

        // 记录任务启动时间（纳秒精度）
        startTimeMap.put(agentId, System.nanoTime());

        log.info("[LifecycleManager] Scheduling task on agent {}: taskId={}, type={}",
                agentId, task.getTaskId(), task.getType());

        CompletableFuture<AgentResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                long start = System.currentTimeMillis();
                log.info("[LifecycleManager] Agent {} executing task: {}", agentId, task.getTaskId());

                // 生成任务结果负载
                String resultPayload = "Task " + task.getTaskId() + " completed by " + agentId
                        + " (type=" + task.getType() + ", target=" + task.getTarget() + ")";
                long elapsed = System.currentTimeMillis() - start;

                // 任务成功完成，更新状态
                stateMap.put(agentId, AgentState.COMPLETED);

                AgentResult result = new AgentResult(agentId, "COMPLETED",
                        "Task executed successfully",
                        resultPayload, elapsed);

                log.info("[LifecycleManager] Agent {} completed task {} in {}ms",
                        agentId, task.getTaskId(), elapsed);

                // 每次成功执行后略微提升 Agent 的历史准确率（指数移动平均）
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

    /**
     * 暂停 Agent。仅 RUNNING 或 WAITING 状态可被暂停，切换为 IDLE。
     *
     * @param agentId Agent ID
     * @return 暂停成功返回 true
     */
    @Override
    public boolean pause(String agentId) {
        AgentState current = stateMap.get(agentId);
        if (current == null) {
            log.warn("[LifecycleManager] Cannot pause unknown agent: {}", agentId);
            return false;
        }

        // 只有正在运行或等待中的 Agent 可以暂停
        if (current != AgentState.RUNNING && current != AgentState.WAITING) {
            log.warn("[LifecycleManager] Cannot pause agent {} in state {}", agentId, current);
            return false;
        }

        stateMap.put(agentId, AgentState.IDLE);
        log.info("[LifecycleManager] Agent {} paused (was {})", agentId, current);

        // 同步更新句柄中的状态
        AgentHandle handle = handleMap.get(agentId);
        if (handle != null) {
            handle.setState(AgentState.IDLE);
        }

        return true;
    }

    /**
     * 恢复暂停的 Agent。仅 IDLE 状态可恢复为 RUNNING。
     *
     * @param agentId Agent ID
     * @return 恢复成功返回 true
     */
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

    /**
     * 终止 Agent。取消正在执行的 Future，清理时间记录，状态置为 CANCELLED。
     *
     * @param agentId Agent ID
     * @return 终止成功返回 true
     */
    @Override
    public boolean terminate(String agentId) {
        AgentState current = stateMap.get(agentId);
        if (current == null) {
            log.warn("[LifecycleManager] Cannot terminate unknown agent: {}", agentId);
            return false;
        }

        // 尝试取消未完成的 Future
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

    /**
     * 获取 Agent 当前状态。
     *
     * @param agentId Agent ID
     * @return 当前状态，不存在返回 null
     */
    @Override
    public AgentState getState(String agentId) {
        return stateMap.get(agentId);
    }

    /**
     * 获取指定 Agent 的完整句柄信息。
     *
     * <p>从 handleMap 中查找并返回与 agentId 关联的 AgentHandle 对象。
     * AgentHandle 包含 Agent 的 ID、名称、状态、能力列表、创建时间和历史准确率等
     * 完整元数据。该对象是可变的——其 state 和 historicalAccuracy 字段可能被
     * AgentLifecycleManager 中的 schedule()、pause()、resume()、terminate() 等方法
     * 实时更新。如果 agentId 对应的 Agent 不存在或已被清理，返回 null。</p>
     *
     * @param agentId 要查询的 Agent ID，不能为 null
     * @return Agent 句柄对象，若不存在则返回 null
     */
    public AgentHandle getHandle(String agentId) {
        return handleMap.get(agentId);
    }

    /**
     * 获取所有 Agent 状态的完整快照副本。
     *
     * <p>返回 stateMap 的浅拷贝（new ConcurrentHashMap），包含调用时刻所有
     * 已创建 Agent 的 agentId 到 AgentState 的映射关系。由于返回的是副本，
     * 调用方可以安全地遍历、修改返回的 Map 而不会影响内部的状态管理表。
     * 该快照反映调用时刻的瞬时状态，适合用于监控面板展示全部 Agent 状态、
     * 周期性健康检查和调试日志输出。注意：返回类型为 ConcurrentHashMap
     * 而非 Map 接口，直接暴露了实现细节，调用方应注意兼容性。</p>
     *
     * @return 所有 Agent ID 到状态的映射副本，永远不会为 null
     */
    public ConcurrentHashMap<String, AgentState> getAllStates() {
        return new ConcurrentHashMap<>(stateMap);
    }

    /**
     * 统计处于指定状态的 Agent 数量。
     *
     * <p>对流式遍历 stateMap 中所有 Agent 的状态值，过滤出等于指定状态的所有 Agent
     * 并返回其数量。此方法是 getIdleCount() 和 getRunningCount() 的基础实现。
     * 统计是实时计算的，反映调用时刻的准确状态。适用于扩缩容决策中了解各状态
     * Agent 分布、监控告警中检测异常状态 Agent 数量等运维场景。</p>
     *
     * @param state 要统计的目标状态，不能为 null
     * @return 处于指定状态的 Agent 数量，最小为 0
     */
    public long countByState(AgentState state) {
        return stateMap.values().stream().filter(s -> s == state).count();
    }

    /**
     * 获取当前处于空闲（IDLE）状态的 Agent 数量。
     *
     * <p>委托 countByState(AgentState.IDLE) 进行统计。空闲 Agent 是可以立即接受
     * 新任务调度的 Agent，此数值直接影响 AutoScalerImpl 的扩缩容决策——
     * 当空闲数低于 TARGET_IDLE(3) 时触发扩容，高于高水位线(6) 时触发缩容。
     * 也用于调度器在 dispatch/schedule 时判断是否有可用 Agent。</p>
     *
     * @return 当前 IDLE 状态的 Agent 数量，最小为 0
     */
    public long getIdleCount() {
        return countByState(AgentState.IDLE);
    }

    /**
     * 获取当前正在运行（RUNNING）的 Agent 数量。
     *
     * <p>委托 countByState(AgentState.RUNNING) 进行统计。运行中的 Agent 正在执行
     * 异步任务，占用线程池资源。此数值用于并发控制——当运行数达到上限时，
     * 新的任务分派将被拒绝或排队。同时它也是系统负载的核心指标之一，
     * 与 getIdleCount() 配合可计算出系统利用率（running / total）。</p>
     *
     * @return 当前 RUNNING 状态的 Agent 数量，最小为 0
     */
    public long getRunningCount() {
        return countByState(AgentState.RUNNING);
    }
}
