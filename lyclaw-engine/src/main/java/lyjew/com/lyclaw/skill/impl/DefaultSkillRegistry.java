package lyjew.com.lyclaw.skill.impl;

import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.skill.SkillGraph;
import lyjew.com.lyclaw.skill.SkillRegistry;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 默认技能注册表实现 —— 使用 ConcurrentHashMap 存储，线程安全。
 *
 * <p>Skill 是比 Tool 更高层的抽象，表示一组有逻辑关联的操作集合。
 * DefaultSkillRegistry 管理所有已注册的 Skill，并维护技能的依赖图，
 * 确保在执行前能检查循环依赖并确定正确的执行顺序。</p>
 *
 * <p><b>与 DefaultToolRegistry 的区别</b>：
 * <ul>
 *   <li>Tool 是最小可执行单元，Skill 是多个 Tool/子 Skill 的编排组合</li>
 *   <li>Tool 没有依赖关系，Skill 可能有（如必须先执行 "用户认证" 才能执行 "查询订单"）</li>
 *   <li>Tool 通过 ToolRegistry 执行，Skill 通过 SkillRegistry 编排</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see SkillRegistry
 * @see SkillGraph
 */
@Component
public class DefaultSkillRegistry implements SkillRegistry {

    /** 技能存储映射 —— key 是技能 ID，value 是 Skill 实例 */
    private final ConcurrentHashMap<String, Skill> skills = new ConcurrentHashMap<>();

    /** 技能依赖图 —— 维护技能之间的依赖关系 */
    private final SkillGraph dependencyGraph;


    /**
     * 构造默认技能注册表。
     *
     * <p>Spring 会自动注入所有 @Component 的 Skill 实现类。</p>
     *
     * @param skillList Spring 自动注入的所有 Skill 实现
     * @param graph     技能依赖图（用于拓扑排序）
     */
    public DefaultSkillRegistry(List<Skill> skillList, SkillGraph graph) {
        this.dependencyGraph = graph;
        // 逐个注册所有注入的技能
        for (Skill skill : skillList) {
            register(skill);
        }
    }

    /**
     * 注册一个技能。
     *
     * @param skill 技能实例，不可为 null
     */
    @Override
    public void register(Skill skill) {
        skills.put(skill.getSkillId(), skill);
    }

    /**
     * 按技能 ID 查找。
     *
     * @param skillId 技能 ID
     * @return Skill 实例，不存在返回 null
     */
    @Override
    public Skill get(String skillId) {
        return skills.get(skillId);
    }

    /**
     * 获取所有已注册的技能。
     *
     * @return 技能列表
     */
    @Override
    public List<Skill> getAll() {
        return List.copyOf(skills.values());
    }

    /**
     * 获取指定技能的依赖项列表。
     *
     * @param skillId 技能 ID
     * @return 依赖的技能 ID 列表
     */
    @Override
    public List<String> getDependencies(String skillId) {
        return dependencyGraph.getDependencies(skillId);
    }

    /**
     * 解析技能的拓扑执行顺序。
     *
     * @return 按依赖顺序排列的技能 ID 列表
     */
    @Override
    public List<String> resolveExecutionOrder() {
        return dependencyGraph.getExecutionOrder();
    }
}