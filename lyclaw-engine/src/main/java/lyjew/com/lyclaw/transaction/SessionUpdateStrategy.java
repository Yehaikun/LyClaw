package lyjew.com.lyclaw.transaction;

import java.util.List;

/**
 * 事务变更合并策略接口 —— 当多次变更对同一字段操作时，决定如何合并。
 *
 * <p>在同一个事务中，同一个字段可能被多次修改（如消息列表连续追加两条消息）。
 * 不同的合并策略有不同的合并结果：
 * <ul>
 *   <li>APPEND：追加模式。新 update 追加到列表末尾（如消息追加场景）</li>
 *   <li>OVERWRITE：覆盖模式。移除同字段的旧 update，保留最新的（如配置更新场景）</li>
 *   <li>DEDUPLICATE：去重模式。移除内容完全相同的重复 update（如幂等操作场景）</li>
 * </ul>
 * </p>
 *
 * <p><b>设计动机</b>：如果不通过策略控制合并行为，回滚时可能错误地恢复到中间状态
 * 而不是事务开始前的状态。不同的变更类型需要不同的合并方式。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public interface SessionUpdateStrategy {

    /**
     * 合并变更记录。根据策略决定如何将新变更插入到已有变更列表中。
     *
     * @param existing  已有的变更记录列表
     * @param newUpdate 新的变更记录
     * @return 合并后的变更记录列表
     */
    List<SessionUpdate> merge(List<SessionUpdate> existing, SessionUpdate newUpdate);

    /**
     * 获取策略名称。
     *
     * @return 策略名称，如 "APPEND"、"OVERWRITE"、"DEDUPLICATE"
     */
    String getStrategyName();
}