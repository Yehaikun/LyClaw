package lyjew.com.lyclaw.agent.scaling;

import lombok.Builder;
import lombok.Data;

/**
 * 代理池快照，记录某个时间点代理池的整体运行状态数据。
 *
 * AgentPoolSnapshot 是自动扩缩容器的输入数据源，由监控组件定期采集
 * 并传递给 AutoScaler.evaluate 方法。它包含代理池的总容量、空闲/运行
 * 中的代理数量、排队任务数及其最大深度、以及目标空闲比例。扩缩容器
 * 通过对比实际闲置比例与目标闲置比例来判断是否需要扩容或缩容，从而
 * 在保证服务响应的同时避免资源浪费。
 *
 * 使用 Lombok 自动生成 getter/setter/Builder 等方法。
 */
@Data
@Builder
public class AgentPoolSnapshot {
    /** 代理池中的代理总数 */
    private int totalAgents;
    /** 当前空闲代理数量 */
    private int idleAgents;
    /** 当前正在执行任务的代理数量 */
    private int runningAgents;
    /** 当前排队的任务数量 */
    private int queuedTasks;
    /** 排队任务的最大容量（队列深度上限） */
    private int maxQueueDepth;
    /** 目标空闲代理比例（0.0 ~ 1.0），用于扩缩容决策的参考基线 */
    private double targetIdleRatio;
}
