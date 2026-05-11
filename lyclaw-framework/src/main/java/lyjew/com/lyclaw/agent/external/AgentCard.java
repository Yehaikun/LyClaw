package lyjew.com.lyclaw.agent.external;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AgentCard {
    private String agentId;
    private String name;
    private String description;
    private String url;
    private String version;
    private List<String> capabilities;
    private List<String> endpoints;
}
