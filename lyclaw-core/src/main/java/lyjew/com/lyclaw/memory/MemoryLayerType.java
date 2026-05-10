package lyjew.com.lyclaw.memory;

/**
 * 记忆层级枚举 —— 四层记忆架构的层级标识。
 *
 * <p>生命周期：
 * <ul>
 *   <li>SENSORY     → 单次对话结束清空</li>
 *   <li>SHORT_TERM  → 会话结束时归档</li>
 *   <li>LONG_TERM   → 永久存储 + 时间衰减</li>
 *   <li>ENTITY      → 永久存储 + 版本管理</li>
 * </ul></p>
 *
 * @since 2.0
 */
public enum MemoryLayerType {
    SENSORY,
    SHORT_TERM,
    LONG_TERM,
    ENTITY
}
