package lyjew.com.lyclaw.agent.communication;

/**
 * A2A 协议中的远程 Agent 定义，包含名称、能力和端点信息。
 */
public class AgentDefinition {
    private String name;
    private String description;
    private String protocol;  // "a2a", "mcp", "custom"
    private String endpoint;  // URL
    private java.util.List<String> capabilities = java.util.Collections.emptyList();

    public AgentDefinition() {}
    public AgentDefinition(String name, String description, String protocol, String endpoint) {
        this.name = name;
        this.description = description;
        this.protocol = protocol;
        this.endpoint = endpoint;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public java.util.List<String> getCapabilities() { return capabilities; }
    public void setCapabilities(java.util.List<String> capabilities) { this.capabilities = capabilities; }

    @Override
    public String toString() {
        return "AgentDefinition{name=" + name + ", protocol=" + protocol + ", endpoint=" + endpoint + "}";
    }
}
