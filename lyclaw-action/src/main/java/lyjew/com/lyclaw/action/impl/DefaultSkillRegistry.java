package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.skill.SkillGraph;
import lyjew.com.lyclaw.skill.SkillRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认技能注册表，管理所有已注册的 {@link Skill} 实例及其依赖关系。
 *
 * <p>内部使用 {@link ConcurrentHashMap} 存储技能，线程安全。
 * 依赖关系管理委托给 {@link SkillGraph}。
 * 构造时自动注册 Spring 容器中所有 Skill 类型的 Bean。</p>
 */
@Slf4j
@Component
public class DefaultSkillRegistry implements SkillRegistry {

    /** 技能存储，以 skillId 为键 */
    private final ConcurrentHashMap<String, Skill> skills = new ConcurrentHashMap<>();
    /** 技能依赖图 */
    private final SkillGraph dependencyGraph;

    /**
     * 构造函数，自动注册传入的所有技能。
     *
     * @param skillList Spring 容器中所有 Skill Bean 的列表
     * @param graph     技能依赖图
     */
    public DefaultSkillRegistry(List<Skill> skillList, SkillGraph graph) {
        this.dependencyGraph = graph;
        if (skillList != null) {
            for (Skill skill : skillList) {
                register(skill);
            }
        }
        log.info("已注册 {} 个技能", skills.size());
    }

    /**
     * 注册技能。同名技能会被覆盖并记录警告。
     *
     * @param skill 技能实例
     */
    @Override
    public void register(Skill skill) {
        Skill old = skills.put(skill.getSkillId(), skill);
        if (old != null) {
            log.warn("同名技能被覆盖: skillId={}", skill.getSkillId());
        }
    }

    /**
     * 注销指定 ID 的技能。
     *
     * @return 被移除的技能，不存在时返回 null
     */
    public Skill unregister(String skillId) {
        return skills.remove(skillId);
    }

    /**
     * 按 ID 查找技能。
     *
     * @return 技能实例，不存在时返回 null
     */
    @Override
    public Skill get(String skillId) {
        return skills.get(skillId);
    }

    /**
     * @return 所有已注册技能的不可变列表
     */
    @Override
    public List<Skill> getAll() {
        return List.copyOf(skills.values());
    }

    /**
     * 获取指定技能的所有依赖。
     *
     * @return 依赖技能 ID 列表
     */
    @Override
    public List<String> getDependencies(String skillId) {
        return dependencyGraph.getDependencies(skillId);
    }

    /**
     * 基于拓扑排序解析技能执行顺序。
     *
     * @return 拓扑排序后的技能 ID 列表
     * @throws IllegalStateException 当存在循环依赖
     */
    @Override
    public List<String> resolveExecutionOrder() {
        return dependencyGraph.getExecutionOrder();
    }

    /** @return 已注册技能总数 */
    public int size() {
        return skills.size();
    }

    /** @return 是否包含指定 ID 的技能 */
    public boolean contains(String skillId) {
        return skills.containsKey(skillId);
    }

    /**
     * 添加技能依赖关系。
     *
     * @param fromSkillId 依赖方
     * @param toSkillId   被依赖方
     */
    public void addDependency(String fromSkillId, String toSkillId) {
        dependencyGraph.addDependency(fromSkillId, toSkillId);
    }

    /** @return 底层技能依赖图实例 */
    public SkillGraph getDependencyGraph() {
        return dependencyGraph;
    }
}
