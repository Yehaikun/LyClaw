package lyjew.com.lyclaw.protocol.a2a;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class A2aArtifact {
    private String artifactId;
    private String taskId;
    private String content;
    private String mimeType;
    private Map<String, Object> metadata;
    private long createdAt;
}
