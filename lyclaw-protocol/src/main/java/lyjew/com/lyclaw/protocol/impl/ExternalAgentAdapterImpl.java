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

/**
 * 外部Agent适配器的默认实现，负责发现外部Agent、发送任务、查询任务状态和取消任务。
 * <p>
 * 使用虚拟线程执行异步操作，通过内存缓存管理已发现的Agent卡片和任务状态。
 * 当前实现为模拟版本，实际使用时需对接真实的A2A协议端点。
 * </p>
 */
@Slf4j
@Service
public class ExternalAgentAdapterImpl implements ExternalAgentAdapter {

    /** 已发现的Agent卡片缓存，key为标准化后的URL */
    private final ConcurrentHashMap<String, AgentCard> discoveredCards = new ConcurrentHashMap<>();
    /** 任务状态追踪，key为任务ID */
    private final ConcurrentHashMap<String, TaskStatus> taskStatuses = new ConcurrentHashMap<>();
    /** 任务元数据，存储agentUrl和开始时间 */
    private final ConcurrentHashMap<String, Map.Entry<String, Long>> taskMetadata = new ConcurrentHashMap<>();
    /** 虚拟线程池，每个任务一个虚拟线程 */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 发现外部Agent，获取其Agent卡片信息。
     *
     * @param endpointUrl 外部Agent的端点URL
     * @return 包含Agent卡片的CompletableFuture，如果URL无效则失败
     */
    @Override
    public CompletableFuture<AgentCard> discover(String endpointUrl) {
        // 参数校验：URL不能为空
        if (endpointUrl == null || endpointUrl.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Endpoint URL must not be null or empty"));
        }

        // 标准化URL：移除末尾斜杠
        String normalisedUrl = endpointUrl.endsWith("/")
                ? endpointUrl.substring(0, endpointUrl.length() - 1)
                : endpointUrl;

        // 检查缓存，避免重复发现
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

                // 生成唯一Agent ID并构建Agent卡片
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

                // 缓存已发现的Agent卡片
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

    /**
     * 向外部Agent发送任务并等待执行结果。
     *
     * @param agentUrl Agent的URL
     * @param task     要执行的任务
     * @param timeout  超时时间，为null时默认300秒
     * @return 包含执行结果的CompletableFuture，支持超时和异常处理
     */
    @Override
    public CompletableFuture<AgentResult> sendTask(String agentUrl, AgentTask task,
                                                    Duration timeout) {
        // 参数校验
        if (agentUrl == null || agentUrl.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Agent URL must not be null or empty"));
        }
        if (task == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Task must not be null"));
        }

        // 默认超时5分钟
        long timeoutMs = timeout != null ? timeout.toMillis() : 300_000L;

        return CompletableFuture.supplyAsync(() -> {
            String taskId = task.getTaskId() != null
                    ? task.getTaskId()
                    : UUID.randomUUID().toString();
            long startTime = System.currentTimeMillis();

            log.info("[ExtAgentAdapter] Sending task to {}: {} (timeout={}ms)",
                    agentUrl, taskId, timeoutMs);

            // 记录任务状态为运行中
            taskStatuses.put(taskId, TaskStatus.RUNNING);
            taskMetadata.put(taskId, new AbstractMap.SimpleEntry<>(agentUrl, startTime));

            try {
                // 模拟任务执行耗时
                long simulatedDuration = Math.min(
                        50 + (long) (Math.random() * 150),
                        timeoutMs);
                Thread.sleep(simulatedDuration);

                long elapsed = System.currentTimeMillis() - startTime;

                // 检查任务是否已被取消
                if (taskStatuses.get(taskId) == TaskStatus.CANCELLED) {
                    log.info("[ExtAgentAdapter] Task {} was cancelled", taskId);
                    return new AgentResult(
                            agentUrl, "CANCELLED",
                            "Task was cancelled",
                            "Task [" + taskId + "] cancelled before completion",
                            elapsed);
                }

                // 标记任务完成
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
                // 线程中断，恢复中断状态
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
                    // 区分超时异常和其他异常
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

    /**
     * 查询指定任务的状态。
     *
     * @param agentUrl Agent的URL
     * @param taskId   任务ID
     * @return 任务状态，如果未找到则返回PENDING
     */
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

    /**
     * 取消指定的任务。
     *
     * @param agentUrl Agent的URL
     * @param taskId   任务ID
     * @return 总是返回true
     */
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

    /**
     * 获取缓存的Agent卡片。
     *
     * @param endpointUrl Agent端点URL
     * @return 缓存的Agent卡片，未找到则返回null
     */
    public AgentCard getCachedCard(String endpointUrl) {
        String normalisedUrl = endpointUrl.endsWith("/")
                ? endpointUrl.substring(0, endpointUrl.length() - 1)
                : endpointUrl;
        return discoveredCards.get(normalisedUrl);
    }

    /**
     * 获取所有已发现的Agent卡片。
     *
     * @return 所有Agent卡片的不可变副本
     */
    public Map<String, AgentCard> getAllDiscoveredCards() {
        return Map.copyOf(discoveredCards);
    }

    /** 清空Agent发现缓存 */
    public void clearDiscoveryCache() {
        discoveredCards.clear();
        log.info("[ExtAgentAdapter] Discovery cache cleared");
    }

    /** 清空任务状态和元数据缓存 */
    public void clearTaskStatuses() {
        taskStatuses.clear();
        taskMetadata.clear();
        log.info("[ExtAgentAdapter] Task status cache cleared");
    }
}
