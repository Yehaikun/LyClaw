package lyjew.com.lyclaw.agent.scaling;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ScalingResult {
    private int previousCount;
    private int newCount;
    private long durationMs;
    private boolean success;
}
