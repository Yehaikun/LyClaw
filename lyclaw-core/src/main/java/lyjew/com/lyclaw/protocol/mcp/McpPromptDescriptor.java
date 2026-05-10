package lyjew.com.lyclaw.protocol.mcp;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class McpPromptDescriptor {

    private String name;
    private String description;
    private List<McpPromptArgument> arguments;

    @Data
    @Builder
    public static class McpPromptArgument {
        private String name;
        private String description;
        private boolean required;
    }
}
