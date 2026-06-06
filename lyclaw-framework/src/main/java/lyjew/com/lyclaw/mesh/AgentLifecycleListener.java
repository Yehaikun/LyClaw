package lyjew.com.lyclaw.mesh;

/**
 * Agent 生命周期监听器 —— 观察者模式。
 *
 * <p>用于监控 Agent 状态变化，如 Supervisor 监听子 Agent 故障并自动恢复。</p>
 */
@FunctionalInterface
public interface AgentLifecycleListener {

    /** 当 Agent 生命周期状态发生变化时调用 */
    void onLifecycleEvent(AgentLifecycleEvent event);
}
