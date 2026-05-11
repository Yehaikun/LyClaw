package lyjew.com.lyclaw.skill;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.SkillResult;

import java.util.concurrent.CompletableFuture;

/**
 * 技能接口，定义技能的基本元数据与执行契约。
 *
 * <p>每个技能拥有唯一标识、名称和描述。通过{@link #execute(ChatContext)}方法
 * 在指定上下文中异步执行。此接口已标记为{@link Deprecated}，新代码应从
 * {@link SkillRegistry}获取技能并通过{@link SkillExecutor}统一调度执行。</p>
 *
 * @deprecated 请使用 {@link SkillExecutor} 和 {@link SkillRegistry} 替代。
 */
@Deprecated
public interface Skill {

    /**
     * 获取技能唯一标识。
     *
     * @return 技能 ID
     */
    String getSkillId();

    /**
     * 获取技能名称。
     *
     * @return 技能名称
     */
    String getName();

    /**
     * 获取技能功能描述。
     *
     * @return 描述文本
     */
    String getDescription();

    /**
     * 异步执行技能逻辑。
     *
     * @param context 对话上下文
     * @return 包含执行结果的异步 Future
     */
    CompletableFuture<SkillResult> execute(ChatContext context);
}
