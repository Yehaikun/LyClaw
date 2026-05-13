package lyjew.com.lyclaw.action.controller;

import lyjew.com.lyclaw.action.ActionExecutor;
import lyjew.com.lyclaw.action.SkillExecuteRequest;
import lyjew.com.lyclaw.action.ToolExecuteRequest;
import lyjew.com.lyclaw.action.impl.ActionExecutorImpl;
import lyjew.com.lyclaw.action.impl.DefaultToolRegistry;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lyjew.com.lyclaw.dto.SkillResult;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.skill.SkillRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import reactor.test.StepVerifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 测试 ActionController 的端点
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActionController 端点测试")
class ActionControllerTest {

    @Mock
    private ActionExecutorImpl actionExecutorImpl;

    @Mock
    private DefaultToolRegistry toolRegistry;

    @Mock
    private SkillRegistry skillRegistry;

    private ActionController controller;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // 手动构造：actionExecutorImpl IS-A ActionExecutor，可同时用于两个位置
        controller = new ActionController(actionExecutorImpl, actionExecutorImpl,
                toolRegistry, skillRegistry);
    }

    @Nested
    @DisplayName("execute-tool 端点")
    class ExecuteTool {

        @Test
        void testExecuteToolWithValidLevel() {
            ToolExecuteRequest req = ToolExecuteRequest.builder()
                    .toolName("calculator")
                    .args(Map.of("expression", "1+1"))
                    .sandboxLevel("read_only")
                    .build();

            ToolExecutionResult toolResult = ToolExecutionResult.builder()
                    .toolName("calculator")
                    .success(true)
                    .result("2")
                    .build();
            when(actionExecutorImpl.executeTool(eq("calculator"), any(), eq(SandboxLevel.READ_ONLY)))
                    .thenReturn(CompletableFuture.completedFuture(toolResult));

            StepVerifier.create(controller.executeTool(req))
                    .assertNext(r -> {
                        assertTrue(r.isSuccess());
                        assertEquals("2", r.getResult());
                    })
                    .verifyComplete();
        }

        @Test
        void testExecuteToolWithInvalidLevelFallsBackToNone() {
            ToolExecuteRequest req = ToolExecuteRequest.builder()
                    .toolName("calculator")
                    .args(Map.of())
                    .sandboxLevel("INVALID_LEVEL")
                    .build();

            ToolExecutionResult toolResult = ToolExecutionResult.builder()
                    .toolName("calculator").success(true).build();
            when(actionExecutorImpl.executeTool(eq("calculator"), any(), eq(SandboxLevel.NONE)))
                    .thenReturn(CompletableFuture.completedFuture(toolResult));

            StepVerifier.create(controller.executeTool(req))
                    .assertNext(r -> assertTrue(r.isSuccess()))
                    .verifyComplete();
        }

        @Test
        void testExecuteToolWithNullLevel() {
            ToolExecuteRequest req = ToolExecuteRequest.builder()
                    .toolName("calculator").args(Map.of()).build();

            ToolExecutionResult toolResult = ToolExecutionResult.builder()
                    .toolName("calculator").success(true).build();
            when(actionExecutorImpl.executeTool(eq("calculator"), any(), eq(SandboxLevel.NONE)))
                    .thenReturn(CompletableFuture.completedFuture(toolResult));

            StepVerifier.create(controller.executeTool(req))
                    .assertNext(r -> assertTrue(r.isSuccess()))
                    .verifyComplete();
        }

        @Test
        void testExecuteToolWithNullArgs() {
            ToolExecuteRequest req = ToolExecuteRequest.builder()
                    .toolName("calculator").build();

            ToolExecutionResult toolResult = ToolExecutionResult.builder()
                    .toolName("calculator").success(true).build();
            when(actionExecutorImpl.executeTool(eq("calculator"), eq(Map.of()), any()))
                    .thenReturn(CompletableFuture.completedFuture(toolResult));

            StepVerifier.create(controller.executeTool(req))
                    .assertNext(r -> assertTrue(r.isSuccess()))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("execute-skill 端点")
    class ExecuteSkill {

        @Test
        void testExecuteSkill() {
            SkillExecuteRequest req = SkillExecuteRequest.builder()
                    .skillId("skill_001").build();

            SkillResult skillResult = new SkillResult("skill_001", true, "ok", null, 0, 0);
            when(actionExecutorImpl.executeSkill(eq("skill_001"), isNull()))
                    .thenReturn(CompletableFuture.completedFuture(skillResult));

            StepVerifier.create(controller.executeSkill(req))
                    .assertNext(r -> {
                        assertTrue(r.isSuccess());
                        assertEquals("skill_001", r.getSkillId());
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("list-tools 端点")
    class ListTools {

        @Test
        void testGetTools() {
            ToolDefinition def = ToolDefinition.builder()
                    .name("calculator").source("builtin").build();
            when(toolRegistry.getAllDefinitions()).thenReturn(List.of(def));

            StepVerifier.create(controller.getTools())
                    .assertNext(defs -> {
                        assertEquals(1, defs.size());
                        assertEquals("calculator", defs.get(0).getName());
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("list-skills 端点")
    class ListSkills {

        @Test
        void testGetSkills() {
            Map<String, Object> skillInfo = new HashMap<>();
            skillInfo.put("skillId", "s1");
            skillInfo.put("name", "TestSkill");
            skillInfo.put("description", "desc");

            when(actionExecutorImpl.getRegisteredSkills())
                    .thenReturn(List.of(skillInfo));

            StepVerifier.create(controller.getSkills())
                    .assertNext(skills -> {
                        assertEquals(1, skills.size());
                        assertEquals("s1", skills.get(0).get("skillId"));
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("sandbox/health 端点")
    class SandboxHealth {

        @Test
        void testGetSandboxHealth() {
            when(actionExecutorImpl.isSandboxHealthy()).thenReturn(true);

            StepVerifier.create(controller.getSandboxHealth())
                    .assertNext(map -> assertTrue((Boolean) map.get("healthy")))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("tools/stats 端点")
    class ToolStats {

        @Test
        void testGetToolStats() {
            when(toolRegistry.size()).thenReturn(5);
            when(toolRegistry.getCategoryStats()).thenReturn(Map.of("builtin", 3L, "mcp", 2L));

            StepVerifier.create(controller.getToolStats())
                    .assertNext(stats -> {
                        assertEquals(5, stats.get("totalCount"));
                        @SuppressWarnings("unchecked")
                        Map<String, Long> catStats = (Map<String, Long>) stats.get("categoryStats");
                        assertEquals(3L, (long) catStats.get("builtin"));
                    })
                    .verifyComplete();
        }
    }
}
