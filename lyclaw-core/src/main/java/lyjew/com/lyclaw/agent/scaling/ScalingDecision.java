package lyjew.com.lyclaw.agent.scaling;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ScalingDecision {

    public enum Action { SCALE_UP, SCALE_DOWN, NONE }

    private Action action;
    private int delta;
    private String reason;
}
