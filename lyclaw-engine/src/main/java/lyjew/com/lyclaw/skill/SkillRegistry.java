package lyjew.com.lyclaw.skill;

import java.util.List;

/**
 * 技能注册中心接口 —— 管理所有 Skill 的注册、查找和依赖解析。
 *
 * <p>技能之间可以存在依赖关系（如"竞品分析"技能依赖"搜索"技能）。
 * SkillRegistry 维护了技能的注册信息和依赖图（通过 {@link SkillGraph}），
 * 并提供拓扑排序的执行顺序。</p>
 *
 * <p><b>设计动机</b>：如果技能之间没有依赖管理系统，编写复合技能时
 * 需要在技能内部硬编码调用其他技能的代码，耦合严重。
 * 通过依赖声明 + 拓扑排序，引擎可以自动编排技能执行顺序。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see SkillGraph
 */
public interface SkillRegistry {

    /**
     * 注册一个技能。同名技能第二次注册抛异常。
     *
     * @param skill 技能实例，不可为 null
     * @throws IllegalArgumentException 如果同名技能已注册
     */
    void register(Skill skill);

    /**
     * 按 ID 查找技能。
     *
     * @param skillId 技能 ID
     * @return 匹配的技能，未找到返回 null
     */
    Skill get(String skillId);

    /**
     * 获取所有已注册的技能。
     *
     * @return 所有已注册的技能列表
     */
    List<Skill> getAll();

    /**
     * 获取指定技能的依赖 ID 列表。
     *
     * @param skillId 技能 ID
     * @return 依赖的技能 ID 列表
     */
    List<String> getDependencies(String skillId);

    /**
     * 对所有已注册技能进行拓扑排序，返回执行顺序。
     * 依赖在前、被依赖在后。
     *
     * @return 按执行顺序排列的技能 ID 列表
     * @throws IllegalStateException 如果检测到循环依赖
     */
    List<String> resolveExecutionOrder();
}