package lyjew.com.lyclaw.protocol.mcp;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class McpToolDescriptor {

    private String name;
    private String description;
    private Map<String, Object> inputSchema;
    private String serverName;
}
