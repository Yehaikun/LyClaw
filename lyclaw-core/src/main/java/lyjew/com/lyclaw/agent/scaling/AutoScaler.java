package lyjew.com.lyclaw.agent.scaling;

import java.util.concurrent.CompletableFuture;

/**
 * Agent 自动伸缩器 —— 根据负载动态调整 Agent 池大小。
 *
 * <p>水位线策略: idle < targetIdle → SCALE_UP, idle > targetIdle×2 → SCALE_DOWN
 * 队列深度策略: queuedTasks > maxQueueDepth → SCALE_UP</p>
 *
 * @since 2.0
 */
public interface AutoScaler {

    ScalingDecision evaluate(AgentPoolSnapshot snapshot);

    CompletableFuture<ScalingResult> apply(ScalingDecision decision);
}
