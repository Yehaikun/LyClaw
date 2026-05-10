package lyjew.com.lyclaw.memory.janitor;

import lyjew.com.lyclaw.memory.JanitorReport;

/**
 * 记忆清理器 —— 定期清理过期、重复、冲突的记忆。
 *
 * <p>去重(语义相似度>0.85) / 过期清理 / 冲突解决 / 分级。
 * 默认每日凌晨 2:00 运行。</p>
 *
 * @since 2.0
 */
public interface MemoryJanitor {

    JanitorReport clean(String userId);

    /** 语义去重阈值 */
    double DEFAULT_DUP_THRESHOLD = 0.85;
}
