package lyjew.com.lyclaw.mesh;

/**
 * Agent 生命周期状态 —— 定义 Agent 实例的运行时状态机。
 *
 * <p>状态转换：
 * <pre>
 * PENDING → STARTING → ACTIVE ⇄ PROGRESS
 *   │                    │          │
 *   │                    ├──→ DEGRADED ──→ ACTIVE (恢复)
 *   │                    │
 *   │                    └──→ STOPPING → STOPPED
 *   │                                       │
 *   └──→ DESTROYED ←─────────── DESTROYED ←┘
 * </pre>
 *
 * <p>所有状态转换都会发布 {@link AgentLifecycleEvent} 到 Mesh 事件总线。</p>
 */
public enum AgentLifecycleState {
    /** Spec 已注册，运行时未初始化 */
    PENDING,
    /** 正在初始化（加载模型、工具、预热） */
    STARTING,
    /** 就绪，可处理消息 */
    ACTIVE,
    /** 正在处理消息（执行 REQUEST） */
    PROGRESS,
    /** 部分失败（如某个工具不可用），但仍可处理消息 */
    DEGRADED,
    /** 正在停止（排空进行中的消息） */
    STOPPING,
    /** 已停止，可重新启动 */
    STOPPED,
    /** 不可恢复的错误 */
    FAILED,
    /** 已销毁，资源已释放 */
    DESTROYED
}
