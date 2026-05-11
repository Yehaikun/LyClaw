package lyjew.com.lyclaw.agent.scaling;

import lombok.Builder;
import lombok.Data;

/**
 * 扩缩容决策，记录自动扩缩容器对代理池的评估结果和操作指令。
 *
 * ScalingDecision 是 AutoScaler.evaluate 的输出，包含了具体的操作
 * 类型（扩容、缩容或无操作）、代理数量的变化量以及决策原因。Action
 * 内嵌枚举的三个值分别对应增加代理、减少代理和维持现状。delta 值
 * 指定了增加或减少的代理数量（对于 NONE 操作该值无效）。reason 为
 * 决策提供可审计的理由说明，便于运维人员理解自动扩缩容的逻辑。
 *
 * 使用 Lombok 自动生成 getter/setter/Builder 等方法。
 */
@Data
@Builder
public class ScalingDecision {

    /** 扩缩容动作类型 */
    public enum Action {
        /** 扩容：增加代理实例 */
        SCALE_UP,
        /** 缩容：减少代理实例 */
        SCALE_DOWN,
        /** 维持现状：不做任何变化 */
        NONE
    }

    /** 本次扩缩容的具体动作 */
    private Action action;
    /** 代理数量的变化量（扩容为正数，缩容为负数，不变时为 0） */
    private int delta;
    /** 决策理由，用于审计和日志记录 */
    private String reason;
}
