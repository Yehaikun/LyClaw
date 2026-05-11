package lyjew.com.lyclaw.skill;

import java.util.List;

/**
 * 技能注册中心接口，提供技能的注册、查找与依赖解析功能。
 *
 * <p>框架中所有可用技能均需通过{@link #register(Skill)}注册到中心。
 * 支持通过技能 ID 检索单个技能或获取全部已注册技能列表。
 * 此接口已标记为{@link Deprecated}，新代码应使用新版技能管理体系。</p>
 *
 * @deprecated 请使用新版技能注册与管理 API 替代。
 */
@Deprecated
public interface SkillRegistry {

    /**
     * 注册一个技能到注册中心。
     *
     * @param skill 待注册的技能实例
     */
    void register(Skill skill);

    /**
     * 根据技能 ID 查找已注册的技能。
     *
     * @param skillId 技能唯一标识
     * @return 找到的技能实例，未找到返回 null
     */
    Skill get(String skillId);

    /**
     * 获取所有已注册技能。
     *
     * @return 技能列表
     */
    List<Skill> getAll();

    /**
     * 获取指定技能的依赖列表。
     *
     * @param skillId 技能标识
     * @return 该技能依赖的其他技能 ID 列表
     */
    List<String> getDependencies(String skillId);

    /**
     * 解析并返回所有技能的拓扑执行顺序。
     *
     * @return 按依赖顺序排列的技能 ID 列表
     */
    List<String> resolveExecutionOrder();
}
