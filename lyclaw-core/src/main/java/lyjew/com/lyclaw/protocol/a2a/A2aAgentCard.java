package lyjew.com.lyclaw.protocol.a2a;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class A2aAgentCard {
    private String agentId;
    private String name;
    private String description;
    private String url;
    private String version;
    private List<AgentCapability> capabilities;
    private List<AgentEndpoint> endpoints;
    private Map<String, String> metadata;
}
