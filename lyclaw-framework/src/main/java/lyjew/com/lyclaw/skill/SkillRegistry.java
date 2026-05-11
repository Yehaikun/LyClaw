package lyjew.com.lyclaw.skill;

import java.util.List;

@Deprecated
public interface SkillRegistry {

    void register(Skill skill);

    Skill get(String skillId);

    List<Skill> getAll();

    List<String> getDependencies(String skillId);

    List<String> resolveExecutionOrder();
}
