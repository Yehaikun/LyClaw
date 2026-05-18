package lyjew.com.lyclaw.react;

import lyjew.com.lyclaw.action.ActionResult;
import lyjew.com.lyclaw.task.ReflectionFeedback;

import java.util.List;

/**
 * Reflexion 自校正循环的执行结果。
 */
public record ReflexionResult(
        String loopId,
        List<Attempt> attempts,
        long totalDurationMs
) {
    public record Attempt(
            int index,
            ActionResult result,
            double qualityScore,
            ReflectionFeedback feedback
    ) {}

    public boolean isSuccess() {
        return attempts != null && !attempts.isEmpty()
                && attempts.getLast().qualityScore() >= 0.3;
    }

    public Attempt getBestAttempt() {
        if (attempts == null || attempts.isEmpty()) return null;
        Attempt best = attempts.get(0);
        for (Attempt a : attempts) {
            if (a.qualityScore() > best.qualityScore()) best = a;
        }
        return best;
    }

    public int getTotalAttempts() {
        return attempts != null ? attempts.size() : 0;
    }
}
