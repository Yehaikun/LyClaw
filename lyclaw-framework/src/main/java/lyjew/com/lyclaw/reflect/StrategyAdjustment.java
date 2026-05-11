package lyjew.com.lyclaw.reflect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 策略调整实体，描述反思引擎对当前执行策略的具体调整建议。
 * 包含调整类型、原因、参数和优先级。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyAdjustment {

    /**
     * 策略调整类型枚举。
     * REWRITE_PROMPT - 重写提示词
     * SWITCH_PLAN_STRATEGY - 切换计划策略（如从串行改为并行）
     * ADD_TOOL_CALL - 增加工具调用
     * REDUCE_TEMPERATURE - 降低温度参数（使输出更确定）
     * INCREASE_TEMPERATURE - 提高温度参数（增加创意性）
     * TRIGGER_HUMAN_INTERVENTION - 触发人工介入
     * RETRY_WITH_CONTEXT - 携带上下文重试
     */
    public enum AdjustmentType {
        REWRITE_PROMPT, SWITCH_PLAN_STRATEGY, ADD_TOOL_CALL,
        REDUCE_TEMPERATURE, INCREASE_TEMPERATURE,
        TRIGGER_HUMAN_INTERVENTION, RETRY_WITH_CONTEXT
    }

    private AdjustmentType type;
    private String reason;
    private Map<String, Object> parameters;
    private double priority;
}
