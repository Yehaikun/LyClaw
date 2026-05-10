package lyjew.com.lyclaw.orchestration.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.agent.scaling.*;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class AutoScalerImpl implements AutoScaler {

    private static final int TARGET_IDLE = 3;
    private static final int SCALE_UP_STEP = 2;
    private static final int SCALE_DOWN_STEP = 1;

    private int currentAgentCount = 3;

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

        if (maxQueueDepth > 0 && queuedTasks > maxQueueDepth) {
            int queueOverflow = queuedTasks - maxQueueDepth;
            int delta = Math.min(SCALE_UP_STEP, Math.max(1, queueOverflow / 2));
            log.info("[AutoScaler] SCALE_UP (queue overflow): queued={}, maxQueueDepth={}, delta={}",
                    queuedTasks, maxQueueDepth, delta);
            return ScalingDecision.builder()
                    .action(ScalingDecision.Action.SCALE_UP)
                    .delta(delta)
                    .reason("Queue overflow: " + queuedTasks + " > " + maxQueueDepth)
                    .build();
        }

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

        log.debug("[AutoScaler] NONE: idle={}, within range [{}, {}]",
                idleAgents, TARGET_IDLE, highWatermark);
        return ScalingDecision.builder()
                .action(ScalingDecision.Action.NONE)
                .delta(0)
                .reason("Idle within acceptable range: " + idleAgents
                        + " in [" + TARGET_IDLE + ", " + highWatermark + "]")
                .build();
    }

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
                    log.info("[AutoScaler] Scale UP applied: {} → {} (delta={}, reason={})",
                            previousCount, newCount, decision.getDelta(), decision.getReason());
                }
                case SCALE_DOWN -> {
                    newCount = Math.max(1, previousCount - decision.getDelta());
                    if (newCount != previousCount) {
                        currentAgentCount = newCount;
                        log.info("[AutoScaler] Scale DOWN applied: {} → {} (delta={}, reason={})",
                                previousCount, newCount, decision.getDelta(), decision.getReason());
                    } else {
                        log.info("[AutoScaler] Scale DOWN blocked: already at minimum (1 agent). reason={}",
                                decision.getReason());
                    }
                }
                default -> log.warn("[AutoScaler] Unknown action: {}", decision.getAction());
            }

            long durationMs = System.currentTimeMillis() - startMs;
            log.info("[AutoScaler] Scaling result: {} → {}, durationMs={}",
                    previousCount, newCount, durationMs);

            return ScalingResult.builder()
                    .previousCount(previousCount)
                    .newCount(newCount)
                    .durationMs(durationMs)
                    .success(true)
                    .build();
        });
    }

    public int getCurrentAgentCount() {
        return currentAgentCount;
    }

    public void setCurrentAgentCount(int count) {
        this.currentAgentCount = Math.max(1, count);
        log.info("[AutoScaler] Agent count manually set to: {}", this.currentAgentCount);
    }
}
