package lyjew.com.lyclaw.orchestration.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.agent.scaling.*;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Agent 自动扩缩容实现。
 *
 * 根据 Agent 池快照评估是否需要扩容或缩容。决策规则：
 * 1. 队列溢出：排队任务超过最大队列深度 -> 扩容
 * 2. 空闲不足：空闲 Agent 少于目标值(3) -> 扩容
 * 3. 空闲过多：空闲 Agent 超过高水位线(6) -> 缩容
 * 4. 否则保持现状
 */
@Slf4j
@Service
public class AutoScalerImpl implements AutoScaler {

    /** 目标空闲 Agent 数量 */
    private static final int TARGET_IDLE = 3;
    /** 每次扩容步长 */
    private static final int SCALE_UP_STEP = 2;
    /** 每次缩容步长 */
    private static final int SCALE_DOWN_STEP = 1;

    /** 当前 Agent 总数（模拟值，生产环境应从实际池中获取） */
    private int currentAgentCount = 3;

    /**
     * 评估当前快照并生成扩缩容决策。
     *
     * @param snapshot Agent 池快照（包含空闲数、排队数等）
     * @return 扩缩容决策（NONE/SCALE_UP/SCALE_DOWN）
     */
    @Override
    public ScalingDecision evaluate(AgentPoolSnapshot snapshot) {
        if (snapshot == null) {
            log.warn("[AutoScaler] Null snapshot, returning NONE");
            return ScalingDecision.builder()
                    .action(ScalingDecision.Action.NONE)
                    .delta(0)
                    .reason("Null snapshot")
                    .build();
        }

        int idleAgents = snapshot.getIdleAgents();
        int queuedTasks = snapshot.getQueuedTasks();
        int maxQueueDepth = snapshot.getMaxQueueDepth();
        double targetIdleRatio = snapshot.getTargetIdleRatio() > 0
                ? snapshot.getTargetIdleRatio() : 0.25;

        log.debug("[AutoScaler] Evaluating: total={}, idle={}, running={}, queued={}, maxQueueDepth={}",
                snapshot.getTotalAgents(), idleAgents, snapshot.getRunningAgents(),
                queuedTasks, maxQueueDepth);

        // 规则1：队列溢出 -> 扩容
        if (maxQueueDepth > 0 && queuedTasks > maxQueueDepth) {
            int queueOverflow = queuedTasks - maxQueueDepth;
            // delta 取队列溢出的一半，但不小于 1，不超过步长上限
            int delta = Math.min(SCALE_UP_STEP, Math.max(1, queueOverflow / 2));
            log.info("[AutoScaler] SCALE_UP (queue overflow): queued={}, maxQueueDepth={}, delta={}",
                    queuedTasks, maxQueueDepth, delta);
            return ScalingDecision.builder()
                    .action(ScalingDecision.Action.SCALE_UP)
                    .delta(delta)
                    .reason("Queue overflow: " + queuedTasks + " > " + maxQueueDepth)
                    .build();
        }

        // 规则2：空闲不足 -> 扩容
        if (idleAgents < TARGET_IDLE) {
            int deficit = TARGET_IDLE - idleAgents;
            int delta = Math.min(SCALE_UP_STEP, Math.max(1, deficit));
            log.info("[AutoScaler] SCALE_UP (low idle): idle={}, target={}, delta={}",
                    idleAgents, TARGET_IDLE, delta);
            return ScalingDecision.builder()
                    .action(ScalingDecision.Action.SCALE_UP)
                    .delta(delta)
                    .reason("Idle agents below target: " + idleAgents + " < " + TARGET_IDLE)
                    .build();
        }

        // 规则3：高水位线 = 目标值的 2 倍，空闲过多 -> 缩容
        int highWatermark = TARGET_IDLE * 2;
        if (idleAgents > highWatermark) {
            int excess = idleAgents - TARGET_IDLE;
            int delta = Math.min(SCALE_DOWN_STEP, Math.max(1, excess / 2));
            log.info("[AutoScaler] SCALE_DOWN (high idle): idle={}, highWatermark={}, delta={}",
                    idleAgents, highWatermark, delta);
            return ScalingDecision.builder()
                    .action(ScalingDecision.Action.SCALE_DOWN)
                    .delta(delta)
                    .reason("Idle agents above high watermark: " + idleAgents + " > " + highWatermark)
                    .build();
        }

        // 规则4：空闲在合理范围内 -> 不变
        log.debug("[AutoScaler] NONE: idle={}, within range [{}, {}]",
                idleAgents, TARGET_IDLE, highWatermark);
        return ScalingDecision.builder()
                .action(ScalingDecision.Action.NONE)
                .delta(0)
                .reason("Idle within acceptable range: " + idleAgents
                        + " in [" + TARGET_IDLE + ", " + highWatermark + "]")
                .build();
    }

    /**
     * 应用扩缩容决策，更新当前 Agent 数量。
     * 缩容时保证数量不低于 1。
     *
     * @param decision 扩缩容决策
     * @return 包含前后数量和耗时的结果 Future
     */
    @Override
    public CompletableFuture<ScalingResult> apply(ScalingDecision decision) {
        return CompletableFuture.supplyAsync(() -> {
            if (decision == null || decision.getAction() == ScalingDecision.Action.NONE) {
                log.debug("[AutoScaler] No scaling action needed, current count={}", currentAgentCount);
                return ScalingResult.builder()
                        .previousCount(currentAgentCount)
                        .newCount(currentAgentCount)
                        .durationMs(0)
                        .success(true)
                        .build();
            }

            long startMs = System.currentTimeMillis();
            int previousCount = currentAgentCount;
            int newCount = previousCount;

            switch (decision.getAction()) {
                case SCALE_UP -> {
                    newCount = previousCount + decision.getDelta();
                    currentAgentCount = newCount;
                    log.info("[AutoScaler] Scale UP applied: {} -> {} (delta={}, reason={})",
                            previousCount, newCount, decision.getDelta(), decision.getReason());
                }
                case SCALE_DOWN -> {
                    newCount = Math.max(1, previousCount - decision.getDelta());  // 保底最少 1 个
                    if (newCount != previousCount) {
                        currentAgentCount = newCount;
                        log.info("[AutoScaler] Scale DOWN applied: {} -> {} (delta={}, reason={})",
                                previousCount, newCount, decision.getDelta(), decision.getReason());
                    } else {
                        log.info("[AutoScaler] Scale DOWN blocked: already at minimum (1 agent). reason={}",
                                decision.getReason());
                    }
                }
                default -> log.warn("[AutoScaler] Unknown action: {}", decision.getAction());
            }

            long durationMs = System.currentTimeMillis() - startMs;
            log.info("[AutoScaler] Scaling result: {} -> {}, durationMs={}",
                    previousCount, newCount, durationMs);

            return ScalingResult.builder()
                    .previousCount(previousCount)
                    .newCount(newCount)
                    .durationMs(durationMs)
                    .success(true)
                    .build();
        });
    }

    /**
     * 获取当前系统中活跃的 Agent 总数。
     *
     * <p>返回内部维护的 currentAgentCount 字段值。该值在系统初始化时默认设为 3，
     * 随后通过 apply() 方法中的 SCALE_UP/SCALE_DOWN 决策自动调整，或通过
     * setCurrentAgentCount() 方法手动设置。注意：在真实生产环境中，此值应从实际的
     * Agent 池（如 AgentRegistry 或 AgentLifecycleManager）中实时获取，当前实现中
     * 它是一个模拟计数器，用于演示扩缩容逻辑的运行效果。</p>
     *
     * @return 当前 Agent 总数，最小值为 1
     */
    public int getCurrentAgentCount() {
        return currentAgentCount;
    }

    /**
     * 手动设置当前 Agent 总数，用于运维人员直接调整集群规模。
     *
     * <p>允许外部调用方（如管理 API、运维脚本或集成测试）绕开扩缩容决策引擎，
     * 直接设定 Agent 数量。方法内部会确保设置的值不小于 1（保底一个 Agent），
     * 防止系统缩容至零导致无法响应任何请求。每次调用都会记录 info 级别日志，
     * 便于追踪手动干预记录。</p>
     *
     * @param count 目标 Agent 数量，若小于 1 则自动修正为 1
     */
    public void setCurrentAgentCount(int count) {
        this.currentAgentCount = Math.max(1, count);
        log.info("[AutoScaler] Agent count manually set to: {}", this.currentAgentCount);
    }
}
