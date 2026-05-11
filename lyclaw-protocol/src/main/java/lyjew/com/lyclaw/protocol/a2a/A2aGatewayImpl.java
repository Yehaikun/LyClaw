package lyjew.com.lyclaw.protocol.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.dto.AgentResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A2A (Agent-to-Agent) 网关实现，负责远程Agent的发现、任务发送、结果获取和本地Agent注册。
 * <p>
 * 使用虚拟线程处理异步操作，通过ConcurrentHashMap管理本地Agent、任务状态和任务产物的缓存。
 * 当前为模拟实现，通过随机延时模拟远程调用。
 * </p>
 */
@Slf4j
@Component
public class A2aGatewayImpl implements A2aGateway {

    /** 本地注册的Agent卡片缓存，key为agentId/URL */
    private final Map<String, A2aAgentCard> localAgents = new ConcurrentHashMap<>();
    /** 任务状态缓存，key为taskId，value为状态字符串 */
    private final Map<String, String> taskStatuses = new ConcurrentHashMap<>();
    /** 任务产物缓存，外层key为taskId，内层key为artifactId */
    private final Map<String, Map<String, A2aArtifact>> taskArtifacts = new ConcurrentHashMap<>();
    /** JSON序列化/反序列化工具 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 虚拟线程执行器，每个任务一个虚拟线程 */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 获取Agent卡片，优先从本地注册的Agent中查找。
     *
     * @param agentUrl Agent的URL
     * @return Agent卡片的CompletableFuture
     */
    @Override
    public CompletableFuture<A2aAgentCard> getAgentCard(String agentUrl) {
        log.info("[A2aGateway] Fetching agent card: {}", agentUrl);

        // 优先返回本地已注册的Agent
        A2aAgentCard local = localAgents.get(agentUrl);
        if (local != null) {
            return CompletableFuture.completedFuture(local);
        }

        return CompletableFuture.supplyAsync(() -> {
            log.info("[A2aGateway] GET {}/.well-known/agent-card.json", agentUrl);

            return A2aAgentCard.builder()
                    .agentId("remote-" + UUID.randomUUID().toString().substring(0, 8))
                    .name("Remote Agent @ " + agentUrl)
                    .description("Auto-discovered remote agent")
                    .url(agentUrl)
                    .version("1.0.0")
                    .capabilities(List.of(
                            AgentCapability.TEXT_GEN,
                            AgentCapability.TOOL_USE))
                    .endpoints(List.of(
                            AgentEndpoint.builder()
                                    .url(agentUrl + "/a2a/task")
                                    .transportType("HTTP")
                                    .primary(true)
                                    .build()))
                    .metadata(Map.of("discovered", "true"))
                    .build();
        }, executor);
    }

    /**
     * 向远程Agent发送A2A任务。
     *
     * @param agentUrl Agent的URL
     * @param task     A2A任务规范
     * @return 包含执行结果的CompletableFuture
     */
    @Override
    public CompletableFuture<AgentResult> sendTask(String agentUrl, A2aTaskSpec task) {
        log.info("[A2aGateway] Sending task to {}: {}", agentUrl,
                task.getTaskId() != null ? task.getTaskId() : task.getDescription());

        return CompletableFuture.supplyAsync(() -> {
            String taskId = task.getTaskId() != null
                    ? task.getTaskId()
                    : UUID.randomUUID().toString();

            log.info("[A2aGateway] POST {}/a2a/task -- taskId={}, description={}",
                    agentUrl, taskId, task.getDescription());

            if (!"CANCELLED".equals(taskStatuses.get(taskId))) {
                taskStatuses.put(taskId, "COMPLETED");
            }

            long simulatedDuration = 100 + (long) (Math.random() * 200);

            return new AgentResult(
                    agentUrl,
                    taskStatuses.getOrDefault(taskId, "COMPLETED"),
                    "Task completed: " + task.getDescription(),
                    "Task [" + taskId + "] executed successfully on agent " + agentUrl
                            + " with parameters: " + task.getParameters(),
                    simulatedDuration);
        }, executor);
    }

    /**
     * 获取任务的产物(artifact)。
     *
     * @param agentUrl   Agent的URL
     * @param taskId     任务ID
     * @param artifactId 产物ID
     * @return 产物的CompletableFuture
     */
    @Override
    public CompletableFuture<A2aArtifact> getArtifact(String agentUrl, String taskId,
                                                       String artifactId) {
        log.info("[A2aGateway] Fetching artifact: taskId={}, artifactId={} from {}",
                taskId, artifactId, agentUrl);

        return CompletableFuture.supplyAsync(() -> {
            Map<String, A2aArtifact> artifacts = taskArtifacts.get(taskId);
            if (artifacts != null) {
                A2aArtifact cached = artifacts.get(artifactId);
                if (cached != null) {
                    log.debug("[A2aGateway] Returning cached artifact: {}", artifactId);
                    return cached;
                }
            }

            log.info("[A2aGateway] GET {}/a2a/artifact?taskId={}&artifactId={}",
                    agentUrl, taskId, artifactId);

            return A2aArtifact.builder()
                    .artifactId(artifactId)
                    .taskId(taskId)
                    .content("Artifact content for " + artifactId
                            + " from task " + taskId + " on agent " + agentUrl)
                    .mimeType("text/plain")
                    .metadata(Map.of("agentUrl", agentUrl, "taskId", taskId))
                    .createdAt(System.currentTimeMillis())
                    .build();
        }, executor);
    }

    /**
     * 取消指定任务。
     *
     * @param agentUrl Agent的URL
     * @param taskId   任务ID
     * @return 总是返回true
     */
    @Override
    public boolean cancelTask(String agentUrl, String taskId) {
        log.info("[A2aGateway] Cancelling task {} on {}", taskId, agentUrl);

        String previous = taskStatuses.put(taskId, "CANCELLED");
        if (previous != null) {
            log.info("[A2aGateway] Task {} cancelled (was {})", taskId, previous);
        } else {
            log.warn("[A2aGateway] Task {} not found in local registry", taskId);
        }

        log.info("[A2aGateway] DELETE {}/a2a/task?taskId={}", agentUrl, taskId);
        return true;
    }

    /**
     * 注册本地Agent卡片。
     *
     * @param card Agent卡片
     */
    @Override
    public void registerLocalAgent(A2aAgentCard card) {
        String agentId = card.getAgentId();
        if (agentId == null || agentId.isEmpty()) {
            agentId = UUID.randomUUID().toString();
        }

        localAgents.put(agentId, card);
        log.info("[A2aGateway] Registered local agent: {} ({}) with {} capabilities",
                agentId, card.getName(),
                card.getCapabilities() != null ? card.getCapabilities().size() : 0);
    }

    /** @return 所有本地Agent的不可变映射 */
    public Map<String, A2aAgentCard> listLocalAgents() {
        return Map.copyOf(localAgents);
    }

    /**
     * 获取任务状态。
     *
     * @param taskId 任务ID
     * @return 任务状态字符串，未找到时返回"UNKNOWN"
     */
    public String getTaskStatus(String taskId) {
        return taskStatuses.getOrDefault(taskId, "UNKNOWN");
    }

    /**
     * 缓存任务产物。
     *
     * @param taskId   任务ID
     * @param artifact 产物对象
     */
    public void cacheArtifact(String taskId, A2aArtifact artifact) {
        taskArtifacts.computeIfAbsent(taskId, k -> new ConcurrentHashMap<>())
                .put(artifact.getArtifactId(), artifact);
        log.debug("[A2aGateway] Cached artifact {} for task {}",
                artifact.getArtifactId(), taskId);
    }
}
