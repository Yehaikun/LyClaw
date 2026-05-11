package lyjew.com.lyclaw.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {

    private String name;

    @Builder.Default
    private String displayName = "";

    private String description;

    private Map<String, Object> parameters;

    @Builder.Default
    private String source = "builtin";

    @Builder.Default
    private String serverName = "";

    @Builder.Default
    private long timeout = 0;

    @Builder.Default
    private boolean readOnly = false;
}
