package lyjew.com.lyclaw.skill;

import lyjew.com.lyclaw.dto.SkillResult;

/**
 * 技能进度回调接口，定义技能执行过程中进度、完成与错误的通知规范。
 *
 * <p>由{@link SkillExecutor}在执行技能时调用，将实时进度、最终结果和异常
 * 信息推送给外部监听方。适合用于 UI 进度条更新、日志记录等场景。</p>
 */
public interface SkillProgressCallback {

    /**
     * 技能执行进度更新回调。
     *
     * @param skillId  技能标识
     * @param progress 当前进度，取值范围 [0.0, 1.0]
     * @param message  进度描述信息
     */
    void onProgress(String skillId, double progress, String message);

    /**
     * 技能执行完成回调。
     *
     * @param skillId 技能标识
     * @param result  技能执行结果
     */
    void onComplete(String skillId, SkillResult result);

    /**
     * 技能执行出错回调。
     *
     * @param skillId 技能标识
     * @param error   异常信息
     */
    void onError(String skillId, Throwable error);
}
