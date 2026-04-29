package lyjew.com.lyclaw.skill;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.SkillResult;

import java.util.concurrent.CompletableFuture;

/**
 * 技能执行器接口 —— 负责技能的异步执行、取消和进度追踪。
 *
 * <p>技能执行是异步的（{@link Skill#execute(ChatContext)} 返回 CompletableFuture），
 * SkillExecutor 在此基础上提供更高级的功能：取消正在执行的技能、
 * 查询执行进度、设置全局进度回调。</p>
 *
 * <p><b>设计动机</b>：一个复杂技能可能执行数秒甚至更久，
 * 调用方可能需要取消、查看进度或接收进度通知。
 * SkillExecutor 将这些能力统一封装。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Skill
 * @see SkillProgressCallback
 */
public interface SkillExecutor {

    /**
     * 异步执行技能。
     *
     * @param skill   要执行的技能，不可为 null
     * @param context 当前对话上下文
     * @return CompletableFuture，完成时包含技能执行结果
     */
    CompletableFuture<SkillResult> execute(Skill skill, ChatContext context);

    /**
     * 取消正在执行的技能。
     *
     * @param skillId 要取消的技能 ID
     * @return true 表示取消成功，false 表示技能不存在或已完成
     */
    boolean cancel(String skillId);

    /**
     * 获取技能执行进度。
     *
     * @param skillId 技能 ID
     * @return 进度值（0.0 ~ 1.0），-1 表示技能不存在
     */
    double getProgress(String skillId);

    /**
     * 设置全局进度回调。
     *
     * @param callback 进度回调接口，不可为 null
     */
    void setProgressCallback(SkillProgressCallback callback);
}