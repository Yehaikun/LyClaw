package lyjew.com.lyclaw.skill.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.SkillResult;
import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.skill.SkillExecutor;
import lyjew.com.lyclaw.skill.SkillProgressCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 默认技能执行器实现 —— 异步执行技能，支持进度追踪和取消。
 *
 * <p><b>核心职责</b>：
 * <ul>
 *   <li>异步执行 {@link Skill}，返回 CompletableFuture&lt;SkillResult&gt;</li>
 *   <li>支持通过 {@link #cancel(String)} 取消正在执行的技能</li>
 *   <li>支持通过 {@link #getProgress(String)} 查询执行进度</li>
 *   <li>支持注入全局进度回调 {@link #setProgressCallback(SkillProgressCallback)}</li>
 * </ul>
 * </p>
 *
 * <p><b>与 Skill.execute() 的区别</b>：
 * <ul>
 *   <li>Skill.execute() 是 Skill 自身的同步/异步执行入口</li>
 *   <li>SkillExecutor 是外部管理器，提供统一的取消、进度查询、回调注册能力</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see SkillExecutor
 * @see Skill
 * @see SkillProgressCallback
 */
@Slf4j
@Component
public class DefaultSkillExecutor implements SkillExecutor {

    /** 技能执行线程池 */
    private final ExecutorService skillExecutor = Executors.newFixedThreadPool(4,
            r -> {
                Thread t = new Thread(r, "skill-worker");
                t.setDaemon(true);
                return t;
            }
    );

    /** 进度 ID 生成器 —— 用于回调中标识当前执行批次 */
    private final AtomicInteger progressIdCounter = new AtomicInteger(0);

    /** 运行中的技能 Future Map —— skillId → CompletableFuture<SkillResult> */
    private final ConcurrentHashMap<String, CompletableFuture<SkillResult>> runningFutures = new ConcurrentHashMap<>();

    /** 技能执行进度 Map —— skillId → progress (0.0 ~ 1.0) */
    private final ConcurrentHashMap<String, Double> progressMap = new ConcurrentHashMap<>();

    /** 全局进度回调（可选） */
    private final AtomicReference<SkillProgressCallback> globalCallback = new AtomicReference<>(null);

    @Override
    public CompletableFuture<SkillResult> execute(Skill skill, ChatContext context) {
        String skillId = skill.getSkillId();
        log.info("[SkillExecutor] 开始执行: skillId={}, name={}", skillId, skill.getName());

        progressMap.put(skillId, 0.0);

        CompletableFuture<SkillResult> future = CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            try {
                progressMap.put(skillId, 0.3);
                notifyProgress(skillId, 0.3, "开始执行技能: " + skill.getName());

                // 调用 Skill 自身的 execute()
                SkillResult result = skill.execute(context)
                        .get(5, TimeUnit.MINUTES);

                long elapsed = System.currentTimeMillis() - startTime;
                progressMap.put(skillId, 1.0);
                notifyProgress(skillId, 1.0, "技能执行完成: " + skill.getName());

                // 如果 Skill 返回的 result 没有 skillId，给一个兜底值
                SkillResult finalResult = result;
                if (finalResult.getSkillId() == null) {
                    finalResult = new SkillResult(skillId, result.isSuccess(),
                            result.getOutput(), result.getError(),
                            result.getTokenUsage(), elapsed);
                }

                log.info("[SkillExecutor] 执行完成: skillId={}, success={}",
                        skillId, result.isSuccess());
                return finalResult;

            } catch (TimeoutException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.warn("[SkillExecutor] 技能执行超时: skillId={}", skillId);
                progressMap.put(skillId, -1.0);
                return new SkillResult(skillId, false, "", "技能执行超时（5分钟）", 0, elapsed);
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.error("[SkillExecutor] 技能执行失败: skillId={}", skillId, e);
                progressMap.put(skillId, -1.0);
                return new SkillResult(skillId, false, "",
                        "技能执行失败: " + e.getMessage(), 0, elapsed);
            }
        }, skillExecutor);

        runningFutures.put(skillId, future);

        // 执行完成后清理
        future.whenComplete((result, ex) -> {
            runningFutures.remove(skillId);
            progressMap.remove(skillId);
        });

        return future;
    }

    @Override
    public boolean cancel(String skillId) {
        CompletableFuture<SkillResult> future = runningFutures.get(skillId);
        if (future == null) return false;

        boolean cancelled = future.cancel(true);
        if (cancelled) {
            progressMap.put(skillId, -1.0);
            log.info("[SkillExecutor] 取消技能: skillId={}", skillId);
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

    /**
     * 通知全局进度回调（如果有设置）。
     *
     * @param skillId  技能 ID
     * @param progress 进度值（0.0 ~ 1.0）
     * @param message  进度消息
     */
    private void notifyProgress(String skillId, double progress, String message) {
        SkillProgressCallback callback = globalCallback.get();
        if (callback != null) {
            try {
                callback.onProgress(skillId, progress, message);
            } catch (Exception e) {
                log.warn("[SkillExecutor] 进度回调异常: skillId={}, error={}",
                        skillId, e.getMessage());
            }
        }
    }
}
