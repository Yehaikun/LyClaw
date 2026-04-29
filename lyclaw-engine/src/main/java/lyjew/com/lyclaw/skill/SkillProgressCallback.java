package lyjew.com.lyclaw.skill;

import lyjew.com.lyclaw.dto.SkillResult;

/**
 * 技能进度回调接口 —— 在技能执行过程中通知调用方。
 *
 * <p>当技能执行时，SkillExecutor 会定期调用回调方法，
 * 让调用方可以实时了解技能的执行进度和状态。
 * 适用于需要展示执行进度的场景（如 WebSocket 推送进度给前端）。</p>
 *
 * <p><b>设计动机</b>：如果不提供回调机制，调用方只能轮询
 * {@link SkillExecutor#getProgress(String)} 来获取进度。
 * 回调方式更高效、更及时。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public interface SkillProgressCallback {

    /**
     * 进度更新时调用。
     *
     * @param skillId  技能 ID
     * @param progress 当前进度（0.0 ~ 1.0）
     * @param message  进度描述文本
     */
    void onProgress(String skillId, double progress, String message);

    /**
     * 技能执行完成时调用。
     *
     * @param skillId 技能 ID
     * @param result  技能执行结果
     */
    void onComplete(String skillId, SkillResult result);

    /**
     * 技能执行出错时调用。
     *
     * @param skillId 技能 ID
     * @param error   捕获的异常或错误
     */
    void onError(String skillId, Throwable error);
}