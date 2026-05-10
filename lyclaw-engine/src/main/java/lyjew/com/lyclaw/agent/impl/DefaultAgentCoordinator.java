package lyjew.com.lyclaw.agent.impl;

import lyjew.com.lyclaw.agent.AgentChannel;
import lyjew.com.lyclaw.agent.AgentCoordinator;
import lyjew.com.lyclaw.agent.AgentState;
import lyjew.com.lyclaw.agent.AgentTask;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.engine.Engine;
import lyjew.com.lyclaw.event.Event;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 默认 Agent 协调器实现 —— 管理 Agent 的创建、调度、状态追踪和取消。
 *
 * <p><b>核心职责</b>：
 * <ul>
 *   <li>接收 {@link AgentTask} 并调度给 Engine 执行</li>
 *   <li>管理 Agent 生命周期（IDLE → RUNNING → COMPLETED/FAILED/CANCELLED）</li>
 *   <li>支持异步执行：{@link #dispatch(ChatContext, AgentTask)} 返回 CompletableFuture</li>
 *   <li>支持超时控制、线程池隔离</li>
 * </ul>
 * </p>
 *
 * <p><b>使用场景</b>：当 ToolCallLoop 或 SkillExecutor 需要 spawn 子任务时，
 * 通过 AgentCoordinator 创建子 Agent 并调度执行。</p>
 *
 * <p><b>线程安全</b>：使用 ConcurrentHashMap 存储 Agent 状态，保证并发调度安全。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see AgentCoordinator
 * @see AgentState
 */
@Slf4j
@Component
public class DefaultAgentCoordinator implements AgentCoordinator {

    /** 第一版最多允许 1 个子 Agent 并发执行（单 Agent 模式） */
    private static final int MAX_CONCURRENT_AGENTS = 1;

    /** 默认超时时间：5 分钟 */
    private static final long DEFAULT_TIMEOUT_MINUTES = 5;

    /** Agent 执行的线程池 —— 与工具执行线程池隔离，避免互相阻塞 */
    private final ExecutorService agentExecutor = Executors.newFixedThreadPool(
            MAX_CONCURRENT_AGENTS,
            r -> {
                Thread t = new Thread(r, "agent-worker");
                t.setDaemon(true);
                return t;
            }
    );

    /** Agent ID 生成器 */
    private final AtomicInteger idCounter = new AtomicInteger(0);

    /** Agent 状态 Map —— agentId → AgentState，ConcurrentHashMap 保证线程安全 */
    private final ConcurrentHashMap<String, AgentState> states = new ConcurrentHashMap<>();

    /** Agent Future Map —— agentId → CompletableFuture<AgentResult> */
    private final ConcurrentHashMap<String, CompletableFuture<AgentResult>> futures = new ConcurrentHashMap<>();

    /** Agent 通信拓扑 —— 第一版使用星型拓扑 */
    private final StarAgentChannel channel;

    /** Agent 启动时间 Map —— agentId → System.nanoTime() */
    private final ConcurrentHashMap<String, Long> startTimes = new ConcurrentHashMap<>();

    /** Agent 绑定的 ChatContext Map —— agentId → ChatContext */
    private final ConcurrentHashMap<String, ChatContext> agentContexts = new ConcurrentHashMap<>();

    /** Engine 引用 —— 子 Agent 通过 Engine 执行对话 */
    private final Engine engine;

    /**
     * 构造 DefaultAgentCoordinator。
     *
     * @param channel Agent 通信拓扑
     * @param engine  用于执行子 Agent 对话的 Engine 实例
     */
    public DefaultAgentCoordinator(StarAgentChannel channel, Engine engine) {
        this.channel = channel;
        this.engine = engine;
    }

    @Override
    public CompletableFuture<AgentResult> dispatch(ChatContext context, AgentTask task) {
        // 1. 检查并发限制 —— 第一版只允许同时 1 个子 Agent
        long runningCount = states.values().stream()
                .filter(s -> s == AgentState.RUNNING || s == AgentState.WAITING)
                .count();
        if (runningCount >= MAX_CONCURRENT_AGENTS) {
            return CompletableFuture.completedFuture(
                    new AgentResult(null, "FAILED",
                            "并发限制：已存在 " + runningCount + " 个运行中的 Agent",
                            "已达到最大并发 Agent 数 " + MAX_CONCURRENT_AGENTS, 0)
            );
        }

        // 2. 生成 agentId
        String agentId = "agent-" + idCounter.incrementAndGet() + "-" + task.getTaskId();
        states.put(agentId, AgentState.IDLE);
        startTimes.put(agentId, System.nanoTime());

        log.info("[AgentCoordinator] 调度 Agent: agentId={}, taskType={}, target={}",
                agentId, task.getType(), task.getTarget());

        // 3. 构建异步任务
        CompletableFuture<AgentResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                // 更新状态为 RUNNING
                states.put(agentId, AgentState.RUNNING);

                // 构建子 Agent 的 ChatRequest
                ChatRequest subRequest = buildSubRequest(context, task);

                // 通过 Engine 执行对话
                StringBuilder resultBuilder = new StringBuilder();
                engine.execute(subRequest)
                        .doOnNext(resultBuilder::append)
                        .blockLast(Duration.ofMinutes(DEFAULT_TIMEOUT_MINUTES));

                String resultText = resultBuilder.toString();
                long elapsed = (System.nanoTime() - startTimes.get(agentId)) / 1_000_000;

                // 更新状态为 COMPLETED
                states.put(agentId, AgentState.COMPLETED);

                AgentResult result = new AgentResult(
                        agentId, "COMPLETED",
                        "Agent 执行完成，返回 " + resultText.length() + " 字符",
                        resultText, elapsed
                );

                log.info("[AgentCoordinator] Agent 完成: agentId={}, elapsed={}ms",
                        agentId, elapsed);
                return result;

            } catch (Exception e) {
                long elapsed = (System.nanoTime() - startTimes.get(agentId)) / 1_000_000;
                states.put(agentId, AgentState.FAILED);

                log.error("[AgentCoordinator] Agent 失败: agentId={}, error={}",
                        agentId, e.getMessage(), e);

                return new AgentResult(
                        agentId, "FAILED",
                        "Agent 执行失败: " + e.getMessage(),
                        e.toString(), elapsed
                );
            }
        }, agentExecutor);

        // 超时控制 —— 超过 DEFAULT_TIMEOUT_MINUTES 则自动取消
        future = future.orTimeout(DEFAULT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        future.exceptionally(ex -> {
            if (ex instanceof TimeoutException) {
                states.put(agentId, AgentState.FAILED);
                long elapsed = (System.nanoTime() - startTimes.get(agentId)) / 1_000_000;
                log.warn("[AgentCoordinator] Agent 超时: agentId={}, timeout={}min",
                        agentId, DEFAULT_TIMEOUT_MINUTES);
                return new AgentResult(agentId, "TIMEOUT",
                        "Agent 执行超时 (" + DEFAULT_TIMEOUT_MINUTES + " 分钟)",
                        "", elapsed);
            }
            return null;
        });

        futures.put(agentId, future);
        agentContexts.put(agentId, context);

        return future;
    }

    @Override
    public boolean cancel(String agentId) {
        CompletableFuture<AgentResult> future = futures.get(agentId);
        if (future == null) return false;

        states.put(agentId, AgentState.CANCELLED);
        boolean cancelled = future.cancel(true);
        log.info("[AgentCoordinator] 取消 Agent: agentId={}, result={}", agentId, cancelled);
        return cancelled;
    }

    @Override
    public AgentState getState(String agentId) {
        return states.getOrDefault(agentId, AgentState.IDLE);
    }

    @Override
    public List<AgentChannel> getChannels(String agentId) {
        return Collections.singletonList(channel);
    }

    @Override
    public void broadcast(Event event) {
        log.info("[AgentCoordinator] 广播事件: type={}", event.getEventType());
        // 第一版广播给所有 Agent 对应的 ChatContext（暂不实现事件转发逻辑）
    }

    /**
     * 从父 Agent 的 ChatContext 和 AgentTask 构建子 Agent 的 ChatRequest。
     *
     * @param parentContext 父 Agent 的对话上下文
     * @param task          要执行的 Agent 任务
     * @return 子 Agent 的 ChatRequest
     */
    private ChatRequest buildSubRequest(ChatContext parentContext, AgentTask task) {
        String taskPrompt = "请执行以下任务：\n" +
                "类型：" + task.getType() + "\n" +
                (task.getTarget() != null ? "目标：" + task.getTarget() + "\n" : "") +
                "任务描述：" + task.getPayload();

        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder()
                .role("user")
                .content(taskPrompt)
                .build());

        return ChatRequest.builder()
                .sessionId(parentContext.getRequest().getSessionId())
                .messages(messages)
                .systemPrompt("你是一个子 Agent，请根据任务描述独立完成工作，返回完整结果。")
                .temperature(0.7)
                .maxTokens(4096)
                .stream(false)
                .build();
    }
}
