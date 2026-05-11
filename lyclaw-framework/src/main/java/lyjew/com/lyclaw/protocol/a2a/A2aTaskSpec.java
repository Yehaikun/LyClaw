package lyjew.com.lyclaw.protocol.a2a;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class A2aTaskSpec {
    private String taskId;
    private String description;
    private List<String> inputArtifacts;
    private Map<String, Object> parameters;
    private int maxRetries;
    private long timeoutMs;
}
