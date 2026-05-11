package lyjew.com.lyclaw.protocol.a2a;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * A2A Agent发现服务，负责通过well-known路径发现远程Agent、注册Agent卡片、按能力查询Agent。
 * <p>
 * 使用内存缓存提升查询性能，支持通过Agent ID或URL查找已发现的Agent。
 * 当前为模拟实现，通过simulateDiscovery生成测试数据。
 * </p>
 */
@Slf4j
@Component
public class A2aDiscovery {

    /** A2A协议标准的Agent卡片发现路径 */
    private static final String WELL_KNOWN_PATH = "/.well-known/agent-card.json";

    /** 已发现Agent的缓存，key为agentId */
    private final ConcurrentHashMap<String, A2aAgentCard> discoveredAgents = new ConcurrentHashMap<>();
    /** URL到agentId的反向索引，用于快速查找 */
    private final ConcurrentHashMap<String, String> urlToAgentId = new ConcurrentHashMap<>();
    /** JSON解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 虚拟线程执行器，用于异步发现远程Agent */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 发现指定URL的远程Agent。
     *
     * @param url Agent的URL
     * @return Agent卡片的CompletableFuture
     */
    public CompletableFuture<A2aAgentCard> discover(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Agent URL must not be null or empty");
        }

        String normalisedUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;

        String existingAgentId = urlToAgentId.get(normalisedUrl);
        if (existingAgentId != null) {
            A2aAgentCard cached = discoveredAgents.get(existingAgentId);
            if (cached != null) {
                log.debug("[A2aDiscovery] Returning cached agent card for: {}", normalisedUrl);
                return CompletableFuture.completedFuture(cached);
            }
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("[A2aDiscovery] Discovering agent at: {}", normalisedUrl);
                log.info("[A2aDiscovery] Fetching: {}", normalisedUrl + WELL_KNOWN_PATH);

                A2aAgentCard card = simulateDiscovery(normalisedUrl);
                registerAgent(card);
                urlToAgentId.put(normalisedUrl, card.getAgentId());

                log.info("[A2aDiscovery] Discovered agent: {} ({} capabilities)",
                        card.getName(),
                        card.getCapabilities() != null ? card.getCapabilities().size() : 0);

                return card;
            } catch (Exception e) {
                log.error("[A2aDiscovery] Failed to discover agent at: {}", normalisedUrl, e);
                throw new RuntimeException("Failed to discover agent at: " + normalisedUrl, e);
            }
        }, executor);
    }

    /**
     * 注册一个Agent卡片到发现缓存中。
     *
     * @param card Agent卡片
     */
    public void registerAgent(A2aAgentCard card) {
        if (card == null) {
            throw new IllegalArgumentException("Agent card must not be null");
        }
        if (card.getAgentId() == null || card.getAgentId().isBlank()) {
            throw new IllegalArgumentException("Agent card must have a non-null agentId");
        }

        discoveredAgents.put(card.getAgentId(), card);
        if (card.getUrl() != null && !card.getUrl().isBlank()) {
            String normalisedUrl = card.getUrl().endsWith("/")
                    ? card.getUrl().substring(0, card.getUrl().length() - 1)
                    : card.getUrl();
            urlToAgentId.put(normalisedUrl, card.getAgentId());
        }

        log.info("[A2aDiscovery] Registered agent: {} ({}) at {}",
                card.getAgentId(), card.getName(), card.getUrl());
    }

    /** @return 所有已发现Agent的不可变映射 */
    public Map<String, A2aAgentCard> getDiscoveredAgents() {
        return Map.copyOf(discoveredAgents);
    }

    /**
     * 按能力查找Agent。
     *
     * @param capability 目标能力
     * @return 具备该能力的Agent列表
     */
    public List<A2aAgentCard> findAgentsByCapability(AgentCapability capability) {
        return discoveredAgents.values().stream()
                .filter(card -> card.getCapabilities() != null
                        && card.getCapabilities().contains(capability))
                .collect(Collectors.toList());
    }

    /**
     * 按Agent ID查找Agent。
     *
     * @param agentId Agent ID
     * @return Agent卡片，未找到则返回null
     */
    public A2aAgentCard findAgent(String agentId) {
        return discoveredAgents.get(agentId);
    }

    /**
     * 移除指定Agent。
     *
     * @param agentId Agent ID
     * @return 是否成功移除
     */
    public boolean removeAgent(String agentId) {
        A2aAgentCard removed = discoveredAgents.remove(agentId);
        if (removed != null && removed.getUrl() != null) {
            String normalisedUrl = removed.getUrl().endsWith("/")
                    ? removed.getUrl().substring(0, removed.getUrl().length() - 1)
                    : removed.getUrl();
            urlToAgentId.remove(normalisedUrl);
        }
        if (removed != null) {
            log.info("[A2aDiscovery] Removed agent: {}", agentId);
        }
        return removed != null;
    }

    /** @return 已发现Agent的总数 */
    public int getAgentCount() {
        return discoveredAgents.size();
    }

    /** 模拟Agent发现过程，生成测试用的Agent卡片数据 */
    private A2aAgentCard simulateDiscovery(String url) {
        String agentId = "discovered-" + UUID.randomUUID().toString().substring(0, 8);

        return A2aAgentCard.builder()
                .agentId(agentId)
                .name("Discovered Agent @ " + url)
                .description("Automatically discovered A2A agent at " + url)
                .url(url)
                .version("1.0.0")
                .capabilities(List.of(
                        AgentCapability.TEXT_GEN,
                        AgentCapability.TOOL_USE,
                        AgentCapability.RAG))
                .endpoints(List.of(
                        AgentEndpoint.builder()
                                .url(url + "/a2a/task")
                                .transportType("HTTP")
                                .primary(true)
                                .build(),
                        AgentEndpoint.builder()
                                .url(url + "/a2a/artifact")
                                .transportType("HTTP")
                                .primary(false)
                                .build()))
                .metadata(Map.of(
                        "discoveryMethod", "well-known",
                        "discoveryTimestamp", String.valueOf(System.currentTimeMillis()),
                        "sourceUrl", url))
                .build();
    }

    /**
     * 从JSON响应解析Agent卡片（预留方法，当前未被调用）。
     *
     * @param jsonBody JSON格式的Agent卡片响应
     * @param url      Agent的URL
     * @return 解析后的Agent卡片
     * @throws JsonProcessingException JSON解析失败时抛出
     */
    @SuppressWarnings("unused")
    private A2aAgentCard parseAgentCard(String jsonBody, String url)
            throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(jsonBody);

        List<AgentCapability> capabilities = new ArrayList<>();
        JsonNode capsNode = root.path("capabilities");
        if (capsNode.isArray()) {
            for (JsonNode cap : capsNode) {
                try {
                    capabilities.add(AgentCapability.valueOf(cap.asText().toUpperCase()));
                } catch (IllegalArgumentException e) {
                    log.warn("[A2aDiscovery] Unknown capability: {}", cap.asText());
                }
            }
        }

        List<AgentEndpoint> endpoints = new ArrayList<>();
        JsonNode epsNode = root.path("endpoints");
        if (epsNode.isArray()) {
            for (JsonNode ep : epsNode) {
                endpoints.add(AgentEndpoint.builder()
                        .url(ep.path("url").asText())
                        .transportType(ep.path("transportType").asText("HTTP"))
                        .primary(ep.path("primary").asBoolean(false))
                        .build());
            }
        }

        return A2aAgentCard.builder()
                .agentId(root.path("agentId").asText(UUID.randomUUID().toString()))
                .name(root.path("name").asText("Unknown Agent"))
                .description(root.path("description").asText(""))
                .url(url)
                .version(root.path("version").asText("1.0.0"))
                .capabilities(capabilities)
                .endpoints(endpoints)
                .metadata(objectMapper.convertValue(root.path("metadata"), Map.class))
                .build();
    }
}
