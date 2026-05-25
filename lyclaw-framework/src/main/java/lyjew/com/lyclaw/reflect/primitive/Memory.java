package lyjew.com.lyclaw.reflect.primitive;

import lyjew.com.lyclaw.reflect.model.MemoryRecord;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;

import java.util.List;

public interface Memory extends ReflectionPrimitive {
    void store(ReflectionContext ctx, MemoryRecord record);
    void storeSkill(ReflectionContext ctx, MemoryRecord skill);
    void storeExperience(ReflectionContext ctx, MemoryRecord experience);
    List<MemoryRecord> retrieve(ReflectionContext ctx, String query, int limit);
    List<MemoryRecord> retrieveSkills(ReflectionContext ctx, String taskDescription, int limit);
    List<MemoryRecord> retrieveExperiences(ReflectionContext ctx, String taskDescription, int limit);
}
