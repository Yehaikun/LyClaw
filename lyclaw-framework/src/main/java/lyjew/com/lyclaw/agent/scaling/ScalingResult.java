package lyjew.com.lyclaw.agent.scaling;

import lombok.Builder;
import lombok.Data;

/**
 * 扩缩容结果，记录一次扩缩容操作执行后的成果。
 *
 * ScalingResult 是 AutoScaler.apply 的输出，记录了扩缩容前后的代理
 * 数量对比、操作耗时以及是否成功。previousCount 和 newCount 让调用方
 * 可以直观看到代理池规模的变化幅度，durationMs 用于监控扩缩容的效率，
 * success 标识操作是否成功完成。如果操作失败，调用方可根据失败信息
 * 决定重试或回退。
 *
 * 使用 Lombok 自动生成 getter/setter/Builder 等方法。
 */
@Data
@Builder
public class ScalingResult {
    /** 扩缩容前的代理数量 */
    private int previousCount;
    /** 扩缩容后的代理数量 */
    private int newCount;
    /** 扩缩容操作耗时（毫秒） */
    private long durationMs;
    /** 操作是否成功 */
    private boolean success;
}
