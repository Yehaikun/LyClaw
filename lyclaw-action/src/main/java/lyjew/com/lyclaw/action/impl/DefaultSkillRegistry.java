package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.skill.SkillGraph;
import lyjew.com.lyclaw.skill.SkillRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class DefaultSkillRegistry implements SkillRegistry {

    private final ConcurrentHashMap<String, Skill> skills = new ConcurrentHashMap<>();
    private final SkillGraph dependencyGraph;

    public DefaultSkillRegistry(List<Skill> skillList, SkillGraph graph) {
        this.dependencyGraph = graph;
        if (skillList != null) {
            for (Skill skill : skillList) {
                register(skill);
            }
        }
        log.info("已注册 {} 个技能", skills.size());
    }

    @Override
    public void register(Skill skill) {
        Skill old = skills.put(skill.getSkillId(), skill);
        if (old != null) {
            log.warn("同名技能被覆盖: skillId={}", skill.getSkillId());
        }
    }

    public Skill unregister(String skillId) {
        return skills.remove(skillId);
    }

    @Override
    public Skill get(String skillId) {
        return skills.get(skillId);
    }

    @Override
    public List<Skill> getAll() {
        return List.copyOf(skills.values());
    }

    @Override
    public List<String> getDependencies(String skillId) {
        return dependencyGraph.getDependencies(skillId);
    }

    @Override
    public List<String> resolveExecutionOrder() {
        return dependencyGraph.getExecutionOrder();
    }

    public int size() {
        return skills.size();
    }

    public boolean contains(String skillId) {
        return skills.containsKey(skillId);
    }

    public void addDependency(String fromSkillId, String toSkillId) {
        dependencyGraph.addDependency(fromSkillId, toSkillId);
    }

    public SkillGraph getDependencyGraph() {
        return dependencyGraph;
    }
}
