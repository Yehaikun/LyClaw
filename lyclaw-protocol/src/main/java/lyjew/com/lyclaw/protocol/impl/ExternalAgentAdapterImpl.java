package lyjew.com.lyclaw.protocol.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.agent.AgentTask;
import lyjew.com.lyclaw.agent.external.AgentCard;
import lyjew.com.lyclaw.agent.external.ExternalAgentAdapter;
import lyjew.com.lyclaw.agent.external.TaskStatus;
import lyjew.com.lyclaw.dto.AgentResult;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
public class ExternalAgentAdapterImpl implements ExternalAgentAdapter {

    private final ConcurrentHashMap<String, AgentCard> discoveredCards = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TaskStatus> taskStatuses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map.Entry<String, Long>> taskMetadata = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public CompletableFuture<AgentCard> discover(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Endpoint URL must not be null or empty"));
        }

        String normalisedUrl = endpointUrl.endsWith("/")
                ? endpointUrl.substring(0, endpointUrl.length() - 1)
                : endpointUrl;

        AgentCard cached = discoveredCards.get(normalisedUrl);
        if (cached != null) {
            log.debug("[ExtAgentAdapter] Returning cached agent card for: {}", normalisedUrl);
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("[ExtAgentAdapter] Discovering external agent at: {}", normalisedUrl);
                String wellKnownUrl = normalisedUrl + "/.well-known/agent-card.json";
                log.info("[ExtAgentAdapter] Fetching: {}", wellKnownUrl);

                String agentId = "ext-" + UUID.randomUUID().toString().substring(0, 8);
                AgentCard card = AgentCard.builder()
                        .agentId(agentId)
                        .name("External Agent @ " + normalisedUrl)
                        .description("Auto-discovered external A2A agent")
                        .url(normalisedUrl)
                        .version("1.0.0")
                        .capabilities(List.of("TEXT_GEN", "TOOL_USE", "RAG"))
                        .endpoints(List.of(
                                normalisedUrl + "/a2a/task",
                                normalisedUrl + "/a2a/artifact"))
                        .build();

                discoveredCards.put(normalisedUrl, card);
                log.info("[ExtAgentAdapter] Discovered agent: {} ({}) with {} capabilities",
                        agentId, card.getName(),
                        card.getCapabilities() != null ? card.getCapabilities().size() : 0);

                return card;
            } catch (Exception e) {
                log.error("[ExtAgentAdapter] Discovery failed for: {}", normalisedUrl, e);
                throw new CompletionException("Failed to discover agent at: " + normalisedUrl, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<AgentResult> sendTask(String agentUrl, AgentTask task,
                                                    Duration timeout) {
        if (agentUrl == null || agentUrl.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Agent URL must not be null or empty"));
        }
        if (task == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Task must not be null"));
        }

        long timeoutMs = timeout != null ? timeout.toMillis() : 300_000L;

        return CompletableFuture.supplyAsync(() -> {
            String taskId = task.getTaskId() != null
                    ? task.getTaskId()
                    : UUID.randomUUID().toString();
            long startTime = System.currentTimeMillis();

            log.info("[ExtAgentAdapter] Sending task to {}: {} (timeout={}ms)",
                    agentUrl, taskId, timeoutMs);

            taskStatuses.put(taskId, TaskStatus.RUNNING);
            taskMetadata.put(taskId, new AbstractMap.SimpleEntry<>(agentUrl, startTime));

            try {
                long simulatedDuration = Math.min(
                        50 + (long) (Math.random() * 150),
                        timeoutMs);
                Thread.sleep(simulatedDuration);

                long elapsed = System.currentTimeMillis() - startTime;

                if (taskStatuses.get(taskId) == TaskStatus.CANCELLED) {
                    log.info("[ExtAgentAdapter] Task {} was cancelled", taskId);
                    return new AgentResult(
                            agentUrl, "CANCELLED",
                            "Task was cancelled",
                            "Task [" + taskId + "] cancelled before completion",
                            elapsed);
                }

                taskStatuses.put(taskId, TaskStatus.COMPLETED);

                String summary = "Task completed: " + task.getType();
                String detail = "Task [" + taskId + "] of type '" + task.getType()
                        + "' completed on agent " + agentUrl
                        + ". Target: " + task.getTarget()
                        + ". Payload length: "
                        + (task.getPayload() != null ? task.getPayload().length() : 0);

                log.info("[ExtAgentAdapter] Task completed: {} ({}ms)", taskId, elapsed);

                return new AgentResult(agentUrl, "COMPLETED", summary, detail, elapsed);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                taskStatuses.put(taskId, TaskStatus.FAILED);
                long elapsed = System.currentTimeMillis() - startTime;
                return new AgentResult(
                        agentUrl, "FAILED",
                        "Task interrupted",
                        "Task [" + taskId + "] was interrupted: " + e.getMessage(),
                        elapsed);
            }
        }, executor).orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    String taskId = task.getTaskId() != null ? task.getTaskId() : "unknown";
                    if (ex instanceof TimeoutException) {
                        taskStatuses.put(taskId, TaskStatus.FAILED);
                        log.warn("[ExtAgentAdapter] Task timeout: {}", taskId);
                        return new AgentResult(
                                agentUrl, "TIMEOUT",
                                "Task timed out after " + timeoutMs + "ms",
                                "Task [" + taskId + "] exceeded timeout",
                                timeoutMs);
                    }
                    taskStatuses.put(taskId, TaskStatus.FAILED);
                    log.error("[ExtAgentAdapter] Task failed: {}", taskId, ex);
                    return new AgentResult(
                            agentUrl, "FAILED",
                            "Task failed: " + ex.getMessage(),
                            "Task [" + taskId + "] failed with error",
                            System.currentTimeMillis() - (
                                    taskMetadata.containsKey(taskId)
                                            ? taskMetadata.get(taskId).getValue()
                                            : System.currentTimeMillis()));
                });
    }

    @Override
    public CompletableFuture<TaskStatus> queryTaskStatus(String agentUrl, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Task ID must not be null or empty"));
        }

        return CompletableFuture.supplyAsync(() -> {
            log.debug("[ExtAgentAdapter] Querying task status: {} on {}", taskId, agentUrl);
            TaskStatus status = taskStatuses.getOrDefault(taskId, TaskStatus.PENDING);
            log.debug("[ExtAgentAdapter] Task {} status: {}", taskId, status);
            return status;
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> cancelTask(String agentUrl, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Task ID must not be null or empty"));
        }

        return CompletableFuture.supplyAsync(() -> {
            log.info("[ExtAgentAdapter] Cancelling task: {} on {}", taskId, agentUrl);
            TaskStatus previous = taskStatuses.put(taskId, TaskStatus.CANCELLED);
            boolean wasActive = previous == TaskStatus.RUNNING || previous == TaskStatus.PENDING;
            log.info("[ExtAgentAdapter] Task {} cancelled (was active: {})", taskId, wasActive);
            return true;
        }, executor);
    }

    public AgentCard getCachedCard(String endpointUrl) {
        String normalisedUrl = endpointUrl.endsWith("/")
                ? endpointUrl.substring(0, endpointUrl.length() - 1)
                : endpointUrl;
        return discoveredCards.get(normalisedUrl);
    }

    public Map<String, AgentCard> getAllDiscoveredCards() {
        return Map.copyOf(discoveredCards);
    }

    public void clearDiscoveryCache() {
        discoveredCards.clear();
        log.info("[ExtAgentAdapter] Discovery cache cleared");
    }

    public void clearTaskStatuses() {
        taskStatuses.clear();
        taskMetadata.clear();
        log.info("[ExtAgentAdapter] Task status cache cleared");
    }
}
