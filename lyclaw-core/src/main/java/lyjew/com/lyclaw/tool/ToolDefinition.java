package lyjew.com.lyclaw.tool;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class ToolDefinition {
    private String name;
    private String description;
    private Map<String, Object> parameters;
    private String category;
}
