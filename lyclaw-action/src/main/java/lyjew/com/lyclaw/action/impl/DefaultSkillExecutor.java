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

/**
 * 默认技能执行器，在后台守护线程池中异步执行技能。
 *
 * <p>提供以下核心能力：
 * <ul>
 *   <li>通过固定大小（4线程）的后台线程池异步提交技能任务</li>
 *   <li>跟踪每个技能的实时进度（0.0 ~ 1.0，-1.0 表示完成）</li>
 *   <li>支持超时（默认5分钟）、取消和通用异常处理</li>
 *   <li>通过全局 {@link SkillProgressCallback} 回调通知进度、完成和错误</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class DefaultSkillExecutor implements SkillExecutor {

    /** 技能执行默认超时时间（分钟） */
    private static final int DEFAULT_TIMEOUT_MINUTES = 5;

    /** 后台守护线程池，专用于技能异步执行 */
    private final ExecutorService skillExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "skill-worker");
        t.setDaemon(true);
        return t;
    });

    /** 正在运行的 CompletableFuture，以 skillId 为键 */
    private final ConcurrentHashMap<String, CompletableFuture<SkillResult>> runningFutures =
            new ConcurrentHashMap<>();

    /** 技能执行进度，键为 skillId，值为 0.0~1.0 的进度，-1.0 表示已完成 */
    private final ConcurrentHashMap<String, Double> progressMap = new ConcurrentHashMap<>();

    /** 全局进度回调，所有技能共享同一个回调实例 */
    private final AtomicReference<SkillProgressCallback> globalCallback = new AtomicReference<>(null);

    /**
     * 异步执行一个技能。
     *
     * <p>在后台线程池中提交技能任务，跟踪进度并在完成/失败/超时时通过回调通知。</p>
     *
     * @param skill   要执行的技能
     * @param context 对话上下文
     * @return 包装 SkillResult 的 CompletableFuture
     */
    @Override
    public CompletableFuture<SkillResult> execute(Skill skill, ChatContext context) {
        String skillId = skill.getSkillId();
        log.info("开始执行: skillId={}, name={}", skillId, skill.getName());

        // 初始化进度为 0.0
        progressMap.put(skillId, 0.0);

        CompletableFuture<SkillResult> future = CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            try {
                updateProgress(skillId, 0.1, "开始执行技能: " + skill.getName());

                // 阻塞等待技能执行结果，带超时保护
                SkillResult result = skill.execute(context)
                        .get(DEFAULT_TIMEOUT_MINUTES, TimeUnit.MINUTES);

                long elapsed = System.currentTimeMillis() - startTime;
                updateProgress(skillId, 1.0, "技能执行完成: " + skill.getName());

                // 如果结果中没有 skillId，补全
                SkillResult finalResult = result;
                if (finalResult.getSkillId() == null || finalResult.getSkillId().isBlank()) {
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
                // 标记为已完成（-1.0）
                progressMap.put(skillId, -1.0);
            }
        }, skillExecutor);

        runningFutures.put(skillId, future);

        // 完成后自动清理
        future.whenComplete((result, ex) -> {
            runningFutures.remove(skillId);
            progressMap.remove(skillId);
        });

        return future;
    }

    /**
     * 取消正在执行的技能。
     *
     * @param skillId 技能标识
     * @return true 表示取消成功，false 表示任务不存在或无法取消
     */
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

    /**
     * 获取指定技能的当前进度。
     *
     * @param skillId 技能标识
     * @return 进度值（0.0~1.0），-1.0 表示该技能未在运行或已结束
     */
    @Override
    public double getProgress(String skillId) {
        return progressMap.getOrDefault(skillId, -1.0);
    }

    /**
     * 设置全局进度回调。所有技能执行时共享同一个回调实例。
     *
     * @param callback 进度回调，为 null 时清除回调
     */
    @Override
    public void setProgressCallback(SkillProgressCallback callback) {
        this.globalCallback.set(callback);
    }

    /** @return 当前正在运行的技能数量 */
    public int getRunningCount() {
        return runningFutures.size();
    }

    /**
     * 更新技能进度并通知回调。
     *
     * @param skillId  技能标识
     * @param progress 进度值（0.0~1.0）
     * @param message  进度消息
     */
    private void updateProgress(String skillId, double progress, String message) {
        progressMap.put(skillId, progress);
        SkillProgressCallback callback = globalCallback.get();
        if (callback != null) {
            try {
                callback.onProgress(skillId, progress, message);
            } catch (Exception e) {
                // 回调异常不中断主流程
                log.warn("进度回调异常: skillId={}", skillId, e);
            }
        }
    }

    /**
     * 通知技能执行完成。
     */
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

    /**
     * 通知技能执行出错。
     */
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
