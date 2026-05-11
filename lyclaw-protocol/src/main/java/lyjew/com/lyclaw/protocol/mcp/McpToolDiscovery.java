package lyjew.com.lyclaw.protocol.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MCP工具发现器，负责从已连接的MCP客户端发现、注册和管理工具描述符。
 * <p>
 * 维护三层索引：按限定名(serverName::toolName)索引、按工具名索引、按服务器名索引，
 * 支持快速的工具查找和按服务器分组查询。
 * </p>
 */
@Slf4j
@Component
public class McpToolDiscovery {

    /** 按限定名索引的所有已发现工具 */
    private final ConcurrentHashMap<String, McpToolDescriptor> discoveredTools = new ConcurrentHashMap<>();
    /** 按工具名索引的工具（可能被同名工具覆盖） */
    private final ConcurrentHashMap<String, McpToolDescriptor> toolsByName = new ConcurrentHashMap<>();
    /** 按服务器名索引的工具名列表 */
    private final ConcurrentHashMap<String, List<String>> serverToolIndex = new ConcurrentHashMap<>();

    /**
     * 从MCP客户端发现并注册所有工具。
     *
     * @param mcpClient MCP客户端
     * @return 所有发现工具的列表
     */
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

    /**
     * 从指定服务器发现工具。
     *
     * @param mcpClient MCP客户端
     * @param serverKey 服务器标识键
     * @return 该服务器的工具列表
     */
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

    /** @return 所有已发现工具的不可变列表 */
    public List<McpToolDescriptor> getAllDiscoveredTools() {
        return List.copyOf(discoveredTools.values());
    }

    /**
     * 按工具名查找工具。
     *
     * @param toolName 工具名称
     * @return 工具描述符，未找到则返回null
     */
    public McpToolDescriptor findToolByName(String toolName) {
        return toolsByName.get(toolName);
    }

    /**
     * 按限定名(serverName::toolName)查找工具。
     *
     * @param qualifiedName 限定名
     * @return 工具描述符，未找到则返回null
     */
    public McpToolDescriptor findToolByQualifiedName(String qualifiedName) {
        return discoveredTools.get(qualifiedName);
    }

    /**
     * 获取指定服务器的所有工具。
     *
     * @param serverName 服务器名称
     * @return 该服务器的工具列表
     */
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

    /** @return 所有已发现服务器名称的集合 */
    public Set<String> getDiscoveredServers() {
        return Set.copyOf(serverToolIndex.keySet());
    }

    /** @return 已发现工具的总数 */
    public int getToolCount() {
        return discoveredTools.size();
    }

    /** 清空所有已发现的工具和索引 */
    public void clear() {
        discoveredTools.clear();
        toolsByName.clear();
        serverToolIndex.clear();
        log.info("[McpToolDiscovery] Cleared all discovered tools");
    }
}
