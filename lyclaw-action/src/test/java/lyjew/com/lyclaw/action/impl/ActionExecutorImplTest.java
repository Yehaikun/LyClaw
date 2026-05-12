package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.action.tool.ToolResult;
import lyjew.com.lyclaw.action.tool.ToolSandbox;
import lyjew.com.lyclaw.dto.SkillResult;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.skill.SkillExecutor;
import lyjew.com.lyclaw.skill.SkillRegistry;
import lyjew.com.lyclaw.task.TaskNode;
import lyjew.com.lyclaw.task.SimpleTaskPlan;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 测试 ActionExecutorImpl 的节点分派和执行逻辑
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActionExecutorImpl 节点分派测试")
class ActionExecutorImplTest {

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private SkillRegistry skillRegistry;

    @Mock
    private ToolSandbox toolSandbox;

    @Mock
    private DefaultToolCallPolicy toolCallPolicy;

    @Mock
    private SkillExecutor skillExecutor;

    private ActionExecutorImpl executor;

    @BeforeEach
    void setUp() {
        lenient().when(toolCallPolicy.canExecute(anyString(), anyInt(), anyString())).thenReturn(true);
        lenient().when(toolCallPolicy.canExecute(anyString(), isNull())).thenReturn(true);
        lenient().when(toolSandbox.isHealthy()).thenReturn(true);
        executor = new ActionExecutorImpl(toolRegistry, skillRegistry,
                toolSandbox, toolCallPolicy, skillExecutor);
    }

    @Nested
    @DisplayName("TaskPlan 执行")
    class TaskPlanExecution {

        @Test
        void testExecuteEmptyPlan() {
            SimpleTaskPlan plan = new SimpleTaskPlan(List.of());
            StepVerifier.create(executor.execute(plan, null))
                    .verifyComplete();
        }

        @Test
        void testExecuteNullPlan() {
            StepVerifier.create(executor.execute(null, null))
                    .verifyComplete();
        }

        @Test
        void testExecuteToolNodeSuccess() throws Exception {
            TaskNode node = new TaskNode("n1", "tool", "calc test",
                    List.of("calculator"), List.of(), 30000);

            Tool mockTool = mock(Tool.class);
            lenient().when(mockTool.getName()).thenReturn("calculator");
            when(toolRegistry.get("calculator")).thenReturn(mockTool);

            when(toolSandbox.execute(eq(mockTool), anyMap(), eq(SandboxLevel.NONE)))
                    .thenReturn(ToolResult.builder()
                            .toolName("calculator")
                            .success(true)
                            .output("42")
                            .durationMs(10)
                            .build());

            SimpleTaskPlan plan = new SimpleTaskPlan(List.of(node));
            StepVerifier.create(executor.execute(plan, null))
                    .assertNext(result -> {
                        assertEquals("n1", result.getNodeId());
                        assertTrue(result.isSuccess());
                        assertEquals("42", result.getOutput());
                    })
                    .verifyComplete();
        }

        @Test
        void testExecuteToolNodeNotFound() throws Exception {
            TaskNode node = new TaskNode("n1", "tool", "missing tool",
                    List.of("no_such_tool"), List.of(), 30000);

            when(toolRegistry.get("no_such_tool")).thenReturn(null);

            SimpleTaskPlan plan = new SimpleTaskPlan(List.of(node));
            StepVerifier.create(executor.execute(plan, null))
                    .assertNext(result -> {
                        assertEquals("n1", result.getNodeId());
                        assertFalse(result.isSuccess());
                        assertTrue(result.getErrorMessage().contains("未注册"));
                    })
                    .verifyComplete();
        }

        @Test
        void testExecuteToolNodeNoRequiredTools() {
            TaskNode node = new TaskNode("n1", "tool", "test",
                    List.of(), List.of(), 30000);

            SimpleTaskPlan plan = new SimpleTaskPlan(List.of(node));
            StepVerifier.create(executor.execute(plan, null))
                    .assertNext(result -> {
                        assertEquals("n1", result.getNodeId());
                        assertFalse(result.isSuccess());
                        assertTrue(result.getErrorMessage().contains("requiredTools"));
                    })
                    .verifyComplete();
        }

        @Test
        void testExecuteSkillNode() throws Exception {
            TaskNode node = new TaskNode("n1", "skill", "test skill",
                    List.of("skill_001"), List.of(), 60000);

            Skill mockSkill = mock(Skill.class);
            lenient().when(mockSkill.getSkillId()).thenReturn("skill_001");
            lenient().when(mockSkill.getName()).thenReturn("TestSkill");
            when(skillRegistry.get("skill_001")).thenReturn(mockSkill);

            SkillResult skillResult = new SkillResult("skill_001", true,
                    "skill output", null, 100, 50);
            when(skillExecutor.execute(eq(mockSkill), isNull()))
                    .thenReturn(CompletableFuture.completedFuture(skillResult));

            SimpleTaskPlan plan = new SimpleTaskPlan(List.of(node));
            StepVerifier.create(executor.execute(plan, null))
                    .assertNext(result -> {
                        assertEquals("n1", result.getNodeId());
                        assertTrue(result.isSuccess());
                        assertEquals("skill output", result.getOutput());
                    })
                    .verifyComplete();
        }

        @Test
        void testExecuteSkillNodeNotFound() throws Exception {
            TaskNode node = new TaskNode("n1", "skill", "missing skill",
                    List.of("no_such_skill"), List.of(), 60000);

            when(skillRegistry.get("no_such_skill")).thenReturn(null);

            SimpleTaskPlan plan = new SimpleTaskPlan(List.of(node));
            StepVerifier.create(executor.execute(plan, null))
                    .assertNext(result -> {
                        assertEquals("n1", result.getNodeId());
                        assertFalse(result.isSuccess());
                        assertTrue(result.getErrorMessage().contains("未注册"));
                    })
                    .verifyComplete();
        }

        @Test
        void testExecuteUnknownNodeType() {
            TaskNode node = new TaskNode("n1", "unknown_type", "test",
                    List.of(), List.of(), 30000);

            SimpleTaskPlan plan = new SimpleTaskPlan(List.of(node));
            StepVerifier.create(executor.execute(plan, null))
                    .assertNext(result -> {
                        assertEquals("n1", result.getNodeId());
                        assertFalse(result.isSuccess());
                        assertTrue(result.getErrorMessage().contains("未知"));
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("类型不敏感分派")
    class CaseInsensitiveDispatch {

        @Test
        void testToolUpperCase() throws Exception {
            TaskNode node = new TaskNode("n1", "TOOL", "test",
                    List.of("calculator"), List.of(), 30000);

            Tool mockTool = mock(Tool.class);
            lenient().when(mockTool.getName()).thenReturn("calculator");
            when(toolRegistry.get("calculator")).thenReturn(mockTool);
            when(toolSandbox.execute(eq(mockTool), anyMap(), eq(SandboxLevel.NONE)))
                    .thenReturn(ToolResult.builder()
                            .toolName("calculator").success(true).output("ok").build());

            SimpleTaskPlan plan = new SimpleTaskPlan(List.of(node));
            StepVerifier.create(executor.execute(plan, null))
                    .assertNext(result -> assertTrue(result.isSuccess()))
                    .verifyComplete();
        }

        @Test
        void testSkillLowerCase() throws Exception {
            TaskNode node = new TaskNode("n1", "Skill", "test",
                    List.of("s1"), List.of(), 60000);

            Skill mockSkill = mock(Skill.class);
            lenient().when(mockSkill.getSkillId()).thenReturn("s1");
            lenient().when(mockSkill.getName()).thenReturn("s1");
            when(skillRegistry.get("s1")).thenReturn(mockSkill);
            when(skillExecutor.execute(eq(mockSkill), isNull()))
                    .thenReturn(CompletableFuture.completedFuture(
                            new SkillResult("s1", true, "ok", null, 0, 0)));

            SimpleTaskPlan plan = new SimpleTaskPlan(List.of(node));
            StepVerifier.create(executor.execute(plan, null))
                    .assertNext(result -> assertTrue(result.isSuccess()))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("工具执行")
    class ToolExecution {

        @Test
        void testExecuteToolSuccess() throws Exception {
            Tool mockTool = mock(Tool.class);
            lenient().when(mockTool.getName()).thenReturn("calculator");
            when(toolRegistry.get("calculator")).thenReturn(mockTool);
            when(toolSandbox.execute(eq(mockTool), anyMap(), eq(SandboxLevel.NONE)))
                    .thenReturn(ToolResult.builder()
                            .toolName("calculator").success(true).output("42").build());

            CompletableFuture<ToolResult> future =
                    executor.executeTool("calculator", Map.of(), SandboxLevel.NONE);
            ToolResult result = future.get();

            assertTrue(result.isSuccess());
            assertEquals("42", result.getOutput());
            assertEquals("calculator", result.getToolName());
        }

        @Test
        void testExecuteToolNotFound() throws Exception {
            when(toolRegistry.get("no_such_tool")).thenReturn(null);

            CompletableFuture<ToolResult> future =
                    executor.executeTool("no_such_tool", Map.of(), SandboxLevel.NONE);
            ToolResult result = future.get();

            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("未注册"));
        }
    }

    @Nested
    @DisplayName("查询方法")
    class QueryMethods {

        @Test
        void testGetRegisteredToolNames() {
            ToolDefinition def = ToolDefinition.builder().name("calculator").source("builtin").build();
            when(toolRegistry.getAllDefinitions()).thenReturn(List.of(def));

            List<String> names = executor.getRegisteredToolNames();
            assertTrue(names.contains("calculator"));
        }

        @Test
        void testGetRegisteredSkills() {
            Skill mockSkill = mock(Skill.class);
            lenient().when(mockSkill.getSkillId()).thenReturn("s1");
            lenient().when(mockSkill.getName()).thenReturn("TestSkill");
            lenient().when(mockSkill.getDescription()).thenReturn("desc");
            when(skillRegistry.getAll()).thenReturn(List.of(mockSkill));

            List<Map<String, Object>> skills = executor.getRegisteredSkills();
            assertEquals(1, skills.size());
            assertEquals("s1", skills.get(0).get("skillId"));
            assertEquals("TestSkill", skills.get(0).get("name"));
        }

        @Test
        void testIsSandboxHealthy() {
            assertTrue(executor.isSandboxHealthy());
        }
    }
}
