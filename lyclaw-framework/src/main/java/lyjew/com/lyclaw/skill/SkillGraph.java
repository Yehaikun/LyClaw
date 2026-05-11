package lyjew.com.lyclaw.skill;

import java.util.List;

public interface SkillGraph {

    void addDependency(String fromSkillId, String toSkillId);

    void removeDependency(String fromSkillId, String toSkillId);

    List<String> getDependencies(String skillId);

    List<String> getDependents(String skillId);

    List<String> getExecutionOrder();

    boolean hasCycle();
}
