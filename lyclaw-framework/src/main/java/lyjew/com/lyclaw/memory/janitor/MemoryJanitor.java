package lyjew.com.lyclaw.memory.janitor;

import lyjew.com.lyclaw.memory.JanitorReport;

/**
 * 记忆清理器接口，负责定期清理过期、重复和冲突的记忆条目。
 *
 * 清理器是记忆系统的维护组件，通常在后台定时运行，扫描指定用户的
 * 记忆存储并移除已过期条目、合并重复内容、解决数据冲突。
 * 清理结果通过 {@link JanitorReport} 返回，便于监控存储空间使用。
 */
public interface MemoryJanitor {

    /** 默认去重相似度阈值，两条记忆向量相似度超过此值视为重复 */
    double DEFAULT_DUP_THRESHOLD = 0.85;

    /**
     * 对指定用户的记忆执行清理操作。
     *
     * 扫描该用户的所有记忆，移除过期条目、合并重复内容并解决冲突。
     *
     * @param userId 用户标识
     * @return 清理操作报告，包含各类清理统计和耗时信息
     */
    JanitorReport clean(String userId);
}
