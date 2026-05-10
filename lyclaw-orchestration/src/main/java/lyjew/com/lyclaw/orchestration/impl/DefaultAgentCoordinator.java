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

@Slf4j
@Component
public class DefaultAgentCoordinator implements AgentCoordinator {

    @Value("${lyclaw.agent.max-concurrent:5}")
    private int maxConcurrentAgents;

    @Value("${lyclaw.agent.timeout-minutes:5}")
    private long defaultTimeoutMinutes;

    private final ExecutorService agentExecutor;
    private final AtomicInteger idCounter = new AtomicInteger(0);
    private final ConcurrentHashMap<String, AgentState> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<AgentResult>> futures = new ConcurrentHashMap<>();
    private final StarAgentChannel channel;
    private final ConcurrentHashMap<String, Long> startTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ChatContext> agentContexts = new ConcurrentHashMap<>();

    @Nullable
    private final Engine engine;

    public DefaultAgentCoordinator(StarAgentChannel channel, @Nullable Engine engine) {
        this.channel = channel;
        this.engine = engine;
        int poolSize = Math.max(1, 5);
        this.agentExecutor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "agent-coordinator-worker");
            t.setDaemon(true);
            return t;
        });
        log.info("[AgentCoordinator] Initialized: maxConcurrent={}, poolSize={}",
                maxConcurrentAgents, poolSize);
    }

    @Override
    public CompletableFuture<AgentResult> dispatch(ChatContext context, AgentTask task) {
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

        String agentId = "agent-" + idCounter.incrementAndGet() + "-" + task.getTaskId();
        states.put(agentId, AgentState.IDLE);
        startTimes.put(agentId, System.nanoTime());

        log.info("[AgentCoordinator] Dispatching agent: agentId={}, taskType={}, target={}",
                agentId, task.getType(), task.getTarget());

        CompletableFuture<AgentResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                states.put(agentId, AgentState.RUNNING);
                ChatRequest subRequest = buildSubRequest(context, task);

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

    @Override
    public boolean cancel(String agentId) {
        CompletableFuture<AgentResult> future = futures.get(agentId);
        if (future == null) return false;

        states.put(agentId, AgentState.CANCELLED);
        boolean cancelled = future.cancel(true);
        log.info("[AgentCoordinator] Cancelled agent: agentId={}, result={}", agentId, cancelled);
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
        log.info("[AgentCoordinator] Broadcasting event: type={}", event.getEventType());
        AgentMessage broadcastMsg = new AgentMessage(
                "coordinator", null,
                event.getEventType(),
                "Event broadcast from coordinator",
                java.time.Instant.now());
        channel.broadcast(broadcastMsg);
    }

    private ChatRequest buildSubRequest(ChatContext parentContext, AgentTask task) {
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
                .sessionId(parentContext.getRequest().getSessionId())
                .messages(messages)
                .systemPrompt("You are a sub-agent. Please independently complete the work based on the task description and return complete results.")
                .temperature(0.7)
                .maxTokens(4096)
                .stream(false)
                .build();
    }

    public long getRunningAgentCount() {
        return states.values().stream()
                .filter(s -> s == AgentState.RUNNING || s == AgentState.WAITING)
                .count();
    }

    public Map<String, AgentState> getAllStates() {
        return new HashMap<>(states);
    }

    public ChatContext getAgentContext(String agentId) {
        return agentContexts.get(agentId);
    }

    public void setMaxConcurrentAgents(int max) {
        this.maxConcurrentAgents = max;
        log.info("[AgentCoordinator] Max concurrent agents updated to: {}", max);
    }
}
