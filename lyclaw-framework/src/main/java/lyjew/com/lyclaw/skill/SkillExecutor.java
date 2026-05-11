package lyjew.com.lyclaw.skill;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.SkillResult;

import java.util.concurrent.CompletableFuture;

/**
 * 技能执行器接口，定义技能的执行、取消、进度查询与回调注册规范。
 *
 * <p>作为技能系统的核心调度入口，负责将{@link Skill}实例在指定的{@link ChatContext}
 * 上下文中异步执行，并通过{@link SkillProgressCallback}向调用方推送执行进度。
 * 与{@link SkillGraph}配合可支持复合技能的依赖解析与编排执行。</p>
 */
public interface SkillExecutor {

    /**
     * 异步执行一个技能。
     *
     * @param skill   待执行的技能实例
     * @param context 当前对话上下文
     * @return 包含技能执行结果的异步 Future
     */
    CompletableFuture<SkillResult> execute(Skill skill, ChatContext context);

    /**
     * 取消指定技能的执行。
     *
     * @param skillId 技能标识
     * @return 取消成功返回 true，若技能不存在或已完成则返回 false
     */
    boolean cancel(String skillId);

    /**
     * 获取指定技能的执行进度。
     *
     * @param skillId 技能标识
     * @return 进度百分比，取值范围 [0.0, 1.0]
     */
    double getProgress(String skillId);

    /**
     * 设置进度回调，用于在执行过程中推送进度更新。
     *
     * @param callback 进度回调实现
     */
    void setProgressCallback(SkillProgressCallback callback);
}
