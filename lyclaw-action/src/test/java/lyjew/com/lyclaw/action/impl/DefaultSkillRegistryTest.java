package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.skill.SkillGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 测试 DefaultSkillRegistry 的注册/查找/依赖关系
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultSkillRegistry 测试")
class DefaultSkillRegistryTest {

    @Mock
    private SkillGraph skillGraph;

    private DefaultSkillRegistry registry;

    /**
     * 创建模拟 Skill，所有 stub 设为 lenient 因为不同测试可能不调用全部方法
     */
    private Skill createMockSkill(String skillId, String name, String description) {
        Skill skill = mock(Skill.class);
        lenient().when(skill.getSkillId()).thenReturn(skillId);
        lenient().when(skill.getName()).thenReturn(name);
        lenient().when(skill.getDescription()).thenReturn(description);
        return skill;
    }

    @Nested
    @DisplayName("注册查找")
    class Registration {

        @Test
        void testRegisterAndGet() {
            registry = new DefaultSkillRegistry(List.of(), skillGraph);
            Skill skill = createMockSkill("s1", "Skill1", "desc");

            registry.register(skill);
            assertEquals(skill, registry.get("s1"));
            assertTrue(registry.contains("s1"));
            assertEquals(1, registry.size());
        }

        @Test
        void testConstructorInitialization() {
            Skill s1 = createMockSkill("s1", "Skill1", "desc1");
            Skill s2 = createMockSkill("s2", "Skill2", "desc2");
            registry = new DefaultSkillRegistry(List.of(s1, s2), skillGraph);

            assertEquals(2, registry.size());
            assertNotNull(registry.get("s1"));
            assertNotNull(registry.get("s2"));
        }

        @Test
        void testOverwriteLogsWarning() {
            registry = new DefaultSkillRegistry(List.of(), skillGraph);
            Skill s1 = createMockSkill("s1", "Skill1_v1", "desc1");
            Skill s2 = createMockSkill("s1", "Skill1_v2", "desc2");

            registry.register(s1);
            assertEquals("Skill1_v1", registry.get("s1").getName());

            registry.register(s2);
            assertEquals("Skill1_v2", registry.get("s1").getName());
            assertEquals(1, registry.size());
        }
    }

    @Nested
    @DisplayName("注销")
    class Unregister {

        @Test
        void testUnregister() {
            registry = new DefaultSkillRegistry(List.of(), skillGraph);
            Skill s1 = createMockSkill("s1", "Skill1", "desc");
            registry.register(s1);

            Skill removed = registry.unregister("s1");
            assertNotNull(removed);
            assertEquals("s1", removed.getSkillId());
            assertEquals(0, registry.size());
        }

        @Test
        void testUnregisterNonExistent() {
            registry = new DefaultSkillRegistry(List.of(), skillGraph);
            assertNull(registry.unregister("nope"));
        }
    }

    @Nested
    @DisplayName("获取所有技能")
    class GetAll {

        @Test
        void testGetAll() {
            Skill s1 = createMockSkill("s1", "S1", "d1");
            Skill s2 = createMockSkill("s2", "S2", "d2");
            registry = new DefaultSkillRegistry(List.of(s1, s2), skillGraph);

            List<Skill> all = registry.getAll();
            assertEquals(2, all.size());
        }

        @Test
        void testGetAllEmpty() {
            registry = new DefaultSkillRegistry(List.of(), skillGraph);
            List<Skill> all = registry.getAll();
            assertTrue(all.isEmpty());
        }
    }

    @Nested
    @DisplayName("依赖管理")
    class Dependencies {

        @BeforeEach
        void setUp() {
            registry = new DefaultSkillRegistry(List.of(), skillGraph);
        }

        @Test
        void testGetDependenciesDelegates() {
            when(skillGraph.getDependencies("s1")).thenReturn(List.of("s0"));
            List<String> deps = registry.getDependencies("s1");
            assertEquals(List.of("s0"), deps);
        }

        @Test
        void testAddDependencyDelegates() {
            registry.addDependency("s1", "s2");
            verify(skillGraph).addDependency("s1", "s2");
        }

        @Test
        void testResolveExecutionOrderDelegates() {
            when(skillGraph.getExecutionOrder()).thenReturn(List.of("s0", "s1", "s2"));
            List<String> order = registry.resolveExecutionOrder();
            assertEquals(List.of("s0", "s1", "s2"), order);
        }

        @Test
        void testGetDependencyGraph() {
            assertEquals(skillGraph, registry.getDependencyGraph());
        }
    }
}
