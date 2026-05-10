package lyjew.com.lyclaw.protocol.mcp;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class McpResourceDescriptor {

    private String uri;
    private String name;
    private String description;
    private String mimeType;
}
