package lyjew.com.lyclaw.protocol.mcp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class McpToolDescriptor {
    private String name;
    private String description;
    private Map<String, Object> inputSchema;
    private String serverName;
}
