package lyjew.com.lyclaw.agent;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class AgentSpec {
    private String name;
    private String description;
    private List<String> capabilities;
    private String modelName;
    private Map<String, Object> config;
}
