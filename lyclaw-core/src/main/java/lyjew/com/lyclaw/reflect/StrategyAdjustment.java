package lyjew.com.lyclaw.reflect;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class StrategyAdjustment {

    public enum AdjustmentType {
        REWRITE_PROMPT,
        SWITCH_PLAN_STRATEGY,
        ADD_TOOL_CALL,
        REDUCE_TEMPERATURE,
        INCREASE_TEMPERATURE,
        TRIGGER_HUMAN_INTERVENTION,
        RETRY_WITH_CONTEXT
    }

    private AdjustmentType type;
    private String reason;
    private Map<String, Object> parameters;
    private double priority;
}
