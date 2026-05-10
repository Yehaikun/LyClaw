package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.SkillResult;
import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.skill.SkillExecutor;
import lyjew.com.lyclaw.skill.SkillProgressCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class DefaultSkillExecutor implements SkillExecutor {

    private static final int DEFAULT_TIMEOUT_MINUTES = 5;

    private final ExecutorService skillExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "skill-worker");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<String, CompletableFuture<SkillResult>> runningFutures =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Double> progressMap = new ConcurrentHashMap<>();

    private final AtomicReference<SkillProgressCallback> globalCallback = new AtomicReference<>(null);

    @Override
    public CompletableFuture<SkillResult> execute(Skill skill, ChatContext context) {
        String skillId = skill.getSkillId();
        log.info("开始执行: skillId={}, name={}", skillId, skill.getName());

        progressMap.put(skillId, 0.0);

        CompletableFuture<SkillResult> future = CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            try {
                updateProgress(skillId, 0.1, "开始执行技能: " + skill.getName());

                SkillResult result = skill.execute(context)
                        .get(DEFAULT_TIMEOUT_MINUTES, TimeUnit.MINUTES);

                long elapsed = System.currentTimeMillis() - startTime;
                updateProgress(skillId, 1.0, "技能执行完成: " + skill.getName());

                SkillResult finalResult = result;
                if (finalResult.getSkillId() == null) {
                    finalResult = new SkillResult(skillId, result.isSuccess(),
                            result.getOutput(), result.getError(),
                            result.getTokenUsage(), elapsed);
                }

                notifyComplete(skillId, finalResult);
                log.info("执行完成: skillId={}, success={}, elapsed={}ms",
                        skillId, result.isSuccess(), elapsed);
                return finalResult;

            } catch (TimeoutException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.warn("技能执行超时: skillId={}, timeout={}min", skillId, DEFAULT_TIMEOUT_MINUTES);
                notifyError(skillId, e);
                return new SkillResult(skillId, false, "",
                        "技能执行超时（" + DEFAULT_TIMEOUT_MINUTES + "分钟）", 0, elapsed);
            } catch (CancellationException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("技能被取消: skillId={}", skillId);
                notifyError(skillId, e);
                return new SkillResult(skillId, false, "", "技能已取消", 0, elapsed);
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.error("技能执行失败: skillId={}", skillId, e);
                notifyError(skillId, e);
                return new SkillResult(skillId, false, "",
                        "技能执行失败: " + e.getMessage(), 0, elapsed);
            } finally {
                progressMap.put(skillId, -1.0);
            }
        }, skillExecutor);

        runningFutures.put(skillId, future);

        future.whenComplete((result, ex) -> {
            runningFutures.remove(skillId);
            progressMap.remove(skillId);
        });

        return future;
    }

    @Override
    public boolean cancel(String skillId) {
        CompletableFuture<SkillResult> future = runningFutures.get(skillId);
        if (future == null) {
            return false;
        }
        boolean cancelled = future.cancel(true);
        if (cancelled) {
            progressMap.put(skillId, -1.0);
            log.info("已取消技能: skillId={}", skillId);
        }
        return cancelled;
    }

    @Override
    public double getProgress(String skillId) {
        return progressMap.getOrDefault(skillId, -1.0);
    }

    @Override
    public void setProgressCallback(SkillProgressCallback callback) {
        this.globalCallback.set(callback);
    }

    public int getRunningCount() {
        return runningFutures.size();
    }

    private void updateProgress(String skillId, double progress, String message) {
        progressMap.put(skillId, progress);
        SkillProgressCallback callback = globalCallback.get();
        if (callback != null) {
            try {
                callback.onProgress(skillId, progress, message);
            } catch (Exception e) {
                log.warn("进度回调异常: skillId={}", skillId, e);
            }
        }
    }

    private void notifyComplete(String skillId, SkillResult result) {
        SkillProgressCallback callback = globalCallback.get();
        if (callback != null) {
            try {
                callback.onComplete(skillId, result);
            } catch (Exception e) {
                log.warn("完成回调异常: skillId={}", skillId, e);
            }
        }
    }

    private void notifyError(String skillId, Throwable error) {
        SkillProgressCallback callback = globalCallback.get();
        if (callback != null) {
            try {
                callback.onError(skillId, error);
            } catch (Exception e) {
                log.warn("错误回调异常: skillId={}", skillId, e);
            }
        }
    }
}
