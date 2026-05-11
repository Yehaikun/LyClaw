package lyjew.com.lyclaw.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 记忆系统运行统计信息的快照对象。
 *
 * 提供各层记忆的数量、token 总量、平均重要度及关键操作的最近执行时间，
 * 供运维监控和调试使用。使用 Lombok 自动生成 getter/setter/Builder 等样板方法。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoryStats {
    /** 感官层感知数据条数 */
    private long perceptionCount;
    /** 短期记忆条目数 */
    private long shortTermCount;
    /** 长期记忆条目数 */
    private long longTermCount;
    /** 实体记忆条目数 */
    private long entityCount;
    /** 所有记忆内容的 token 总量估算 */
    private long totalTokens;
    /** 所有条目的平均重要性评分 */
    private double avgImportance;
    /** 最近一次固化操作的时间戳（毫秒） */
    private long lastConsolidationTime;
    /** 最近一次清理操作的时间戳（毫秒） */
    private long lastJanitorRunTime;
}
