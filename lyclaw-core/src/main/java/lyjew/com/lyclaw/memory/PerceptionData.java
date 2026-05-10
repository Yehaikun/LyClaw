package lyjew.com.lyclaw.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PerceptionData {
    private String role;
    private String content;
    private long timestamp;
    private List<String> toolCallIds;
    private Map<String, Object> metadata;
}
