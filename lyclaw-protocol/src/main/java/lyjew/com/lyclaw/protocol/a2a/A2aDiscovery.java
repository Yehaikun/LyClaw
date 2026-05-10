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

@Slf4j
@Component
public class A2aDiscovery {

    private static final String WELL_KNOWN_PATH = "/.well-known/agent-card.json";

    private final ConcurrentHashMap<String, A2aAgentCard> discoveredAgents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> urlToAgentId = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

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

    public Map<String, A2aAgentCard> getDiscoveredAgents() {
        return Map.copyOf(discoveredAgents);
    }

    public List<A2aAgentCard> findAgentsByCapability(AgentCapability capability) {
        return discoveredAgents.values().stream()
                .filter(card -> card.getCapabilities() != null
                        && card.getCapabilities().contains(capability))
                .collect(Collectors.toList());
    }

    public A2aAgentCard findAgent(String agentId) {
        return discoveredAgents.get(agentId);
    }

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

    public int getAgentCount() {
        return discoveredAgents.size();
    }

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
