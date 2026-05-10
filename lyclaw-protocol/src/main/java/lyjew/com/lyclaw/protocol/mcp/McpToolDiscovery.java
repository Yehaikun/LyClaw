package lyjew.com.lyclaw.protocol.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
public class McpToolDiscovery {

    private final ConcurrentHashMap<String, McpToolDescriptor> discoveredTools = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, McpToolDescriptor> toolsByName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> serverToolIndex = new ConcurrentHashMap<>();

    public List<McpToolDescriptor> discoverAndRegister(McpClient mcpClient) {
        if (mcpClient == null) {
            log.warn("[McpToolDiscovery] McpClient is null, nothing to discover");
            return Collections.emptyList();
        }

        log.info("[McpToolDiscovery] Starting tool discovery across {} connected servers",
                mcpClient.listConnectedServers().size());

        discoveredTools.clear();
        toolsByName.clear();
        serverToolIndex.clear();

        List<McpToolDescriptor> allTools = mcpClient.discoverTools();

        for (McpToolDescriptor tool : allTools) {
            String serverName = tool.getServerName() != null
                    ? tool.getServerName() : "unknown";
            String qualifiedName = serverName + "::" + tool.getName();

            discoveredTools.put(qualifiedName, tool);
            toolsByName.put(tool.getName(), tool);

            serverToolIndex.computeIfAbsent(serverName, k -> new ArrayList<>())
                    .add(tool.getName());

            log.debug("[McpToolDiscovery] Discovered tool: {} ({})",
                    tool.getName(),
                    tool.getDescription() != null
                            ? tool.getDescription()
                            : "no description");
        }

        log.info("[McpToolDiscovery] Discovery complete: {} tools from {} servers",
                allTools.size(), serverToolIndex.size());

        return allTools;
    }

    public List<McpToolDescriptor> discoverFromServer(McpClient mcpClient, String serverKey) {
        if (mcpClient == null || serverKey == null) {
            return Collections.emptyList();
        }

        if (!mcpClient.isConnected(serverKey)) {
            log.warn("[McpToolDiscovery] Server not connected: {}", serverKey);
            return Collections.emptyList();
        }

        List<McpToolDescriptor> allTools = discoverAndRegister(mcpClient);

        return allTools.stream()
                .filter(t -> serverKey.equals(
                        t.getServerName() != null ? t.getServerName() : null)
                        || (t.getServerName() != null
                        && t.getServerName().contains(serverKey)))
                .collect(Collectors.toList());
    }

    public List<McpToolDescriptor> getAllDiscoveredTools() {
        return List.copyOf(discoveredTools.values());
    }

    public McpToolDescriptor findToolByName(String toolName) {
        return toolsByName.get(toolName);
    }

    public McpToolDescriptor findToolByQualifiedName(String qualifiedName) {
        return discoveredTools.get(qualifiedName);
    }

    public List<McpToolDescriptor> getToolsForServer(String serverName) {
        List<String> toolNames = serverToolIndex.get(serverName);
        if (toolNames == null) {
            return Collections.emptyList();
        }
        return toolNames.stream()
                .map(toolsByName::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public Set<String> getDiscoveredServers() {
        return Set.copyOf(serverToolIndex.keySet());
    }

    public int getToolCount() {
        return discoveredTools.size();
    }

    public void clear() {
        discoveredTools.clear();
        toolsByName.clear();
        serverToolIndex.clear();
        log.info("[McpToolDiscovery] Cleared all discovered tools");
    }
}
