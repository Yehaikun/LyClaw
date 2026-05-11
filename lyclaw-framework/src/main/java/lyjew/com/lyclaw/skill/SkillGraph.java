package lyjew.com.lyclaw.skill;

import java.util.List;

/**
 * 技能依赖图接口，管理技能之间的依赖关系并提供拓扑排序与环检测能力。
 *
 * <p>用于复合技能（{@link SkillType#COMPOSITE}）的场景，其中子技能之间存在
 * 执行顺序依赖。通过{@link #getExecutionOrder()}获取拓扑排序后的执行顺序，
 * 通过{@link #hasCycle()}检测是否存在循环依赖。</p>
 */
public interface SkillGraph {

    /**
     * 添加一条依赖关系：fromSkill 依赖于 toSkill（即 toSkill 必须先执行）。
     *
     * @param fromSkillId 依赖方技能 ID
     * @param toSkillId   被依赖方技能 ID
     */
    void addDependency(String fromSkillId, String toSkillId);

    /**
     * 移除一条依赖关系。
     *
     * @param fromSkillId 依赖方技能 ID
     * @param toSkillId   被依赖方技能 ID
     */
    void removeDependency(String fromSkillId, String toSkillId);

    /**
     * 获取指定技能的所有直接依赖（该技能依赖的其他技能）。
     *
     * @param skillId 技能 ID
     * @return 依赖的技能 ID 列表
     */
    List<String> getDependencies(String skillId);

    /**
     * 获取依赖指定技能的所有技能（即哪些技能依赖了它）。
     *
     * @param skillId 技能 ID
     * @return 依赖于该技能的所有技能 ID 列表
     */
    List<String> getDependents(String skillId);

    /**
     * 获取拓扑排序后的执行顺序。
     *
     * @return 技能 ID 列表，按执行顺序排列
     */
    List<String> getExecutionOrder();

    /**
     * 检测依赖图中是否存在循环依赖。
     *
     * @return 存在环返回 true
     */
    boolean hasCycle();
}
