package lyjew.com.lyclaw.react;

/**
 * 上下文压缩生命周期钩子。
 */
public interface CompactionLifecycleHook {

    /** 上下文压缩前调用。 */
    default void beforeCompaction(AgentContext ctx) {}

    /** 上下文压缩后调用。 */
    default void afterCompaction(AgentContext ctx) {}
}
