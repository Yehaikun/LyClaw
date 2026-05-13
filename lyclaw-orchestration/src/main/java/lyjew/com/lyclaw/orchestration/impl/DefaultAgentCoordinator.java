package lyjew.com.lyclaw.orchestration.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.agent.*;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.engine.Engine;
import lyjew.com.lyclaw.event.Event;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 协调器默认实现。
 *
 * 负责将任务分派给 Agent 并管理其执行生命周期。
 * 支持并发限制、超时控制和取消操作。
 * 通过 StarAgentChannel 实现 Agent 间的通信，通过 Engine 执行 Agent 的子任务。
 * 使用固定线程池（daemon 线程）执行 Agent 任务，防止阻塞主线程。
 */
@Slf4j
@Component
public class DefaultAgentCoordinator implements AgentCoordinator {

    /** 最大并发 Agent 数量，可通过配置覆盖 */
    @Value("${lyclaw.agent.max-concurrent:5}")
    private int maxConcurrentAgents;

    /** Agent 任务默认超时时间（分钟） */
    @Value("${lyclaw.agent.timeout-minutes:5}")
    private long defaultTimeoutMinutes;

    private final ExecutorService agentExecutor;
    private final AtomicInteger idCounter = new AtomicInteger(0);
    /** Agent 状态表 */
    private final ConcurrentHashMap<String, AgentState> states = new ConcurrentHashMap<>();
    /** Agent 异步 Future 表 */
    private final ConcurrentHashMap<String, CompletableFuture<AgentResult>> futures = new ConcurrentHashMap<>();
    /** Agent 通信通道 */
    private final StarAgentChannel channel;
    /** Agent 启动时间记录（纳秒） */
    private final ConcurrentHashMap<String, Long> startTimes = new ConcurrentHashMap<>();
    /** Agent 上下文映射 */
    private final ConcurrentHashMap<String, ChatContext> agentContexts = new ConcurrentHashMap<>();

    /** 执行引擎（可选），用于执行子 Agent 的 LLM 请求 */
    @Nullable
    private final Engine engine;

    public DefaultAgentCoordinator(StarAgentChannel channel, @Nullable Engine engine) {
        this.channel = channel;
        this.engine = engine;
        int poolSize = Math.max(1, 5);
        this.agentExecutor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "agent-coordinator-worker");
            t.setDaemon(true);  // 守护线程，不阻止 JVM 退出
            return t;
        });
        log.info("[AgentCoordinator] Initialized: maxConcurrent={}, poolSize={}",
                maxConcurrentAgents, poolSize);
    }

    /**
     * 分派任务给 Agent 执行。
     *
     * 流程：检查并发限制 -> 创建 Agent -> 切换为 RUNNING -> 异步执行 ->
     * 设置超时 -> 完成后切换 COMPLETED/FAILED/TIMEOUT
     *
     * @param context 聊天上下文
     * @param task    要执行的任务
     * @return 包含 AgentResult 的 Future
     */
    @Override
    public CompletableFuture<AgentResult> dispatch(ChatContext context, AgentTask task) {
        // 检查并发上限：统计当前 RUNNING 和 WAITING 状态的 Agent 数量
        long runningCount = states.values().stream()
                .filter(s -> s == AgentState.RUNNING || s == AgentState.WAITING)
                .count();
        int limit = maxConcurrentAgents > 0 ? maxConcurrentAgents : 5;
        if (runningCount >= limit) {
            log.warn("[AgentCoordinator] Concurrency limit reached: running={}, max={}",
                    runningCount, limit);
            return CompletableFuture.completedFuture(
                    new AgentResult(null, "FAILED",
                            "Concurrency limit: " + runningCount + " agents running (max " + limit + ")",
                            "Max concurrent agents reached", 0));
        }

        // 生成 Agent ID 并初始化状态
        String agentId = "agent-" + idCounter.incrementAndGet() + "-" + task.getTaskId();
        states.put(agentId, AgentState.IDLE);
        startTimes.put(agentId, System.nanoTime());

        log.info("[AgentCoordinator] Dispatching agent: agentId={}, taskType={}, target={}",
                agentId, task.getType(), task.getTarget());

        // 异步执行 Agent 任务
        CompletableFuture<AgentResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                states.put(agentId, AgentState.RUNNING);
                ChatRequest subRequest = buildSubRequest(context, task);

                // 通过 Engine 执行子任务（流式），收集所有输出
                StringBuilder resultBuilder = new StringBuilder();
                if (engine != null) {
                    engine.execute(subRequest)
                            .doOnNext(resultBuilder::append)
                            .blockLast(Duration.ofMinutes(defaultTimeoutMinutes));
                } else {
                    resultBuilder.append("[AgentCoordinator] No Engine available, task not executed: ")
                            .append(task.getType());
                }

                String resultText = resultBuilder.toString();
                long elapsed = (System.nanoTime() - startTimes.get(agentId)) / 1_000_000;
                states.put(agentId, AgentState.COMPLETED);

                AgentResult result = new AgentResult(
                        agentId, "COMPLETED",
                        "Agent completed, returned " + resultText.length() + " chars",
                        resultText, elapsed);

                log.info("[AgentCoordinator] Agent completed: agentId={}, elapsed={}ms",
                        agentId, elapsed);
                return result;

            } catch (Exception e) {
                long elapsed = startTimes.containsKey(agentId)
                        ? (System.nanoTime() - startTimes.get(agentId)) / 1_000_000 : 0;
                states.put(agentId, AgentState.FAILED);
                log.error("[AgentCoordinator] Agent failed: agentId={}, error={}",
                        agentId, e.getMessage(), e);
                return new AgentResult(
                        agentId, "FAILED",
                        "Agent execution failed: " + e.getMessage(),
                        e.toString(), elapsed);
            }
        }, agentExecutor);

        // 设置超时：超过 defaultTimeoutMinutes 分钟自动失败
        future = future.orTimeout(defaultTimeoutMinutes, TimeUnit.MINUTES);
        future.exceptionally(ex -> {
            if (ex instanceof TimeoutException) {
                states.put(agentId, AgentState.FAILED);
                long elapsed = startTimes.containsKey(agentId)
                        ? (System.nanoTime() - startTimes.get(agentId)) / 1_000_000 : 0;
                log.warn("[AgentCoordinator] Agent timeout: agentId={}, timeout={}min",
                        agentId, defaultTimeoutMinutes);
                return new AgentResult(agentId, "TIMEOUT",
                        "Agent execution timeout (" + defaultTimeoutMinutes + " min)",
                        "", elapsed);
            }
            return null;
        });

        futures.put(agentId, future);
        agentContexts.put(agentId, context);

        return future;
    }

    /**
     * 取消指定 Agent 的执行。
     *
     * @param agentId Agent ID
     * @return 取消成功返回 true
     */
    @Override
    public boolean cancel(String agentId) {
        CompletableFuture<AgentResult> future = futures.get(agentId);
        if (future == null) return false;

        states.put(agentId, AgentState.CANCELLED);
        boolean cancelled = future.cancel(true);  // 中断正在执行的线程
        log.info("[AgentCoordinator] Cancelled agent: agentId={}, result={}", agentId, cancelled);
        return cancelled;
    }

    /**
     * 获取 Agent 状态，默认返回 IDLE。
     */
    @Override
    public AgentState getState(String agentId) {
        return states.getOrDefault(agentId, AgentState.IDLE);
    }

    /**
     * 获取 Agent 的通信通道列表。
     */
    @Override
    public List<AgentChannel> getChannels(String agentId) {
        return Collections.singletonList(channel);
    }

    /**
     * 向所有 Agent 广播事件。
     * 通过 StarAgentChannel 的广播机制推送给所有已连接 Agent。
     */
    @Override
    public void broadcast(Event event) {
        log.info("[AgentCoordinator] Broadcasting event: type={}", event.getEventType());
        AgentMessage broadcastMsg = new AgentMessage(
                "coordinator", null,
                event.getEventType(),
                "Event broadcast from coordinator",
                java.time.Instant.now());
        channel.broadcast(broadcastMsg);
    }

    /**
     * 根据父上下文和任务构建子 Agent 的 ChatRequest。
     * 构造任务提示词，设置子 Agent 的系统提示、温度等参数。
     */
    private ChatRequest buildSubRequest(ChatContext parentContext, AgentTask task) {
        // 组装任务描述作为用户消息
        String taskPrompt = "Please execute the following task:\n" +
                "Type: " + task.getType() + "\n" +
                (task.getTarget() != null ? "Target: " + task.getTarget() + "\n" : "") +
                "Description: " + task.getPayload();

        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder()
                .role("user")
                .content(taskPrompt)
                .build());

        return ChatRequest.builder()
                .sessionId(parentContext.getRequest().getSessionId())  // 继承父会话
                .messages(messages)
                .systemPrompt("You are a sub-agent. Please independently complete the work based on the task description and return complete results.")
                .temperature(0.7)
                .maxTokens(4096)
                .stream(false)  // 子 Agent 非流式执行
                .build();
    }

    /**
     * 获取当前正在运行或等待中的 Agent 数量。
     *
     * <p>通过流式遍历 states 映射中所有 Agent 的状态，统计状态为 RUNNING 或 WAITING
     * 的 Agent 总数。该计数用于 dispatch() 方法中的并发控制——当此数量达到
     * maxConcurrentAgents 上限时，新的任务分派请求将被拒绝。返回值实时反映当前
     * 系统的并发负载情况，可用于监控面板展示和自动扩缩容决策。</p>
     *
     * @return 当前处于 RUNNING 或 WAITING 状态的 Agent 数量，最小为 0
     */
    public long getRunningAgentCount() {
        return states.values().stream()
                .filter(s -> s == AgentState.RUNNING || s == AgentState.WAITING)
                .count();
    }

    /**
     * 获取所有 Agent 状态的快照副本。
     *
     * <p>返回 states 映射的浅拷贝（new HashMap），包含调用时刻所有已注册 Agent 的
     * agentId 到 AgentState 的映射关系。由于返回的是副本，调用方可以安全地遍历和
     * 修改返回的 Map 而不会影响内部状态表。该快照反映了调用时刻的瞬时状态，
     * 适合用于监控面板展示、周期性状态巡检和调试日志输出。</p>
     *
     * @return 包含所有 Agent ID 到状态的映射副本，永远不会为 null
     */
    public Map<String, AgentState> getAllStates() {
        return new HashMap<>(states);
    }

    /**
     * 获取指定 Agent 的聊天上下文。
     *
     * <p>从 agentContexts 映射中查找并返回与该 Agent ID 关联的 ChatContext 对象。
     * ChatContext 在 dispatch() 方法中被存入，包含了父会话信息、用户请求内容和
     * 追踪数据等。如果 Agent 已执行完毕或被清理但上下文尚未移除，仍可获取到。
     * 返回值可能为 null（Agent 不存在或上下文已被清理）。</p>
     *
     * @param agentId 要查询的 Agent ID，不能为 null
     * @return 关联的聊天上下文，若不存在则返回 null
     */
    public ChatContext getAgentContext(String agentId) {
        return agentContexts.get(agentId);
    }

    /**
     * 动态调整最大并发 Agent 数量限制。
     *
     * <p>允许在运行时修改 dispatch() 方法中的并发上限，无需重启服务。
     * 新的限制值将在下一次 dispatch() 调用时立即生效。调整后记录 info 级别日志
     * 以便审计追踪。初始值通过 Spring 配置属性 lyclaw.agent.max-concurrent 注入，
     * 默认值为 5。注意：降低此值不会影响已在运行中的 Agent，仅阻止新任务的接入。</p>
     *
     * @param max 新的最大并发 Agent 数量，必须为正整数
     */
    public void setMaxConcurrentAgents(int max) {
        this.maxConcurrentAgents = max;
        log.info("[AgentCoordinator] Max concurrent agents updated to: {}", max);
    }
}
