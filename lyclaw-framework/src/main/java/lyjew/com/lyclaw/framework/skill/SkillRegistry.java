package lyjew.com.lyclaw.framework.skill;

import java.util.List;

public interface SkillRegistry {

    void register(Skill skill);

    Skill get(String skillId);

    List<Skill> getAll();

    List<String> getDependencies(String skillId);

    List<String> resolveExecutionOrder();
}
