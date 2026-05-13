package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 测试 DefaultToolRegistry 的注册/查找/去重逻辑
 */
@DisplayName("DefaultToolRegistry 注册/查找/去重测试")
class DefaultToolRegistryTest {

    private DefaultToolRegistry registry;

    private Tool createMockTool(String name, String source) {
        Tool tool = mock(Tool.class);
        when(tool.getName()).thenReturn(name);
        when(tool.getDefinition()).thenReturn(
                ToolDefinition.builder()
                        .name(name)
                        .source(source)
                        .description("Mock tool: " + name)
                        .build());
        return tool;
    }

    @Nested
    @DisplayName("基本注册查找")
    class BasicRegistration {

        @Test
        void testRegisterAndGet() {
            registry = new DefaultToolRegistry(List.of());
            Tool tool = createMockTool("calculator", "builtin");
            registry.register(tool);
            assertEquals(tool, registry.get("calculator"));
            assertTrue(registry.contains("calculator"));
        }

        @Test
        void testConstructorInitialization() {
            Tool t1 = createMockTool("t1", "builtin");
            Tool t2 = createMockTool("t2", "mcp");
            registry = new DefaultToolRegistry(List.of(t1, t2));

            assertEquals(2, registry.size());
            assertNotNull(registry.get("t1"));
            assertNotNull(registry.get("t2"));
        }

        @Test
        void testGetNonExistent() {
            registry = new DefaultToolRegistry(List.of());
            assertNull(registry.get("non_existent"));
            assertFalse(registry.contains("non_existent"));
        }

        @Test
        void testNullToolThrows() {
            registry = new DefaultToolRegistry(List.of());
            assertThrows(IllegalArgumentException.class, () -> registry.register(null));
        }
    }

    @Nested
    @DisplayName("覆盖/去重逻辑")
    class OverwriteAndDedup {

        @Test
        void testOverwriteLogsWarning() {
            registry = new DefaultToolRegistry(List.of());
            Tool t1 = createMockTool("same_name", "builtin");
            Tool t2 = createMockTool("same_name", "mcp");

            registry.register(t1);
            assertEquals("builtin", registry.get("same_name").getDefinition().getSource());

            registry.register(t2);
            assertEquals("mcp", registry.get("same_name").getDefinition().getSource());
            assertEquals(1, registry.size());
        }
    }

    @Nested
    @DisplayName("注销/清空")
    class UnregisterAndClear {

        @Test
        void testUnregister() {
            Tool tool = createMockTool("t1", "builtin");
            registry = new DefaultToolRegistry(List.of(tool));

            Tool removed = registry.unregister("t1");
            assertNotNull(removed);
            assertEquals("t1", removed.getName());
            assertEquals(0, registry.size());
        }

        @Test
        void testUnregisterNonExistentReturnsNull() {
            registry = new DefaultToolRegistry(List.of());
            assertNull(registry.unregister("nope"));
        }

        @Test
        void testClear() {
            Tool t1 = createMockTool("t1", "builtin");
            Tool t2 = createMockTool("t2", "mcp");
            registry = new DefaultToolRegistry(List.of(t1, t2));

            registry.clear();
            assertEquals(0, registry.size());
        }
    }

    @Nested
    @DisplayName("获取定义列表")
    class Definitions {

        @Test
        void testGetAllDefinitions() {
            Tool t1 = createMockTool("t1", "builtin");
            Tool t2 = createMockTool("t2", "mcp");
            registry = new DefaultToolRegistry(List.of(t1, t2));

            List<ToolDefinition> defs = registry.getAllDefinitions();
            assertEquals(2, defs.size());
            assertTrue(defs.stream().anyMatch(d -> d.getName().equals("t1")));
            assertTrue(defs.stream().anyMatch(d -> d.getName().equals("t2")));
        }

        @Test
        void testGetAllDefinitionsEmpty() {
            registry = new DefaultToolRegistry(List.of());
            List<ToolDefinition> defs = registry.getAllDefinitions();
            assertTrue(defs.isEmpty());
        }
    }

    @Nested
    @DisplayName("工具名集合")
    class ToolNames {

        @Test
        void testGetToolNames() {
            Tool t1 = createMockTool("t1", "builtin");
            Tool t2 = createMockTool("t2", "mcp");
            registry = new DefaultToolRegistry(List.of(t1, t2));

            Set<String> names = registry.getToolNames();
            assertEquals(2, names.size());
            assertTrue(names.contains("t1"));
            assertTrue(names.contains("t2"));
        }
    }

    @Nested
    @DisplayName("execute 方法")
    class Execute {

        @Test
        void testExecuteDelegates() {
            Tool tool = mock(Tool.class);
            when(tool.getName()).thenReturn("calc");
            ToolDefinition def = ToolDefinition.builder().name("calc").source("builtin").build();
            when(tool.getDefinition()).thenReturn(def);

            ToolExecutionResult innerResult = ToolExecutionResult.builder().success(true).result("42").elapsedMs(10).build();
            ToolCall call = ToolCall.builder().name("calc").arguments("{}").build();
            when(tool.execute(eq(call), isNull())).thenReturn(innerResult);

            registry = new DefaultToolRegistry(List.of(tool));

            ToolExecutionResult result = registry.execute(call, null);
            assertTrue(result.isSuccess());
            assertEquals("42", result.getResult());
        }

        @Test
        void testExecuteThrowsOnMissing() {
            registry = new DefaultToolRegistry(List.of());
            ToolCall call = ToolCall.builder().name("missing").arguments("{}").build();

            assertThrows(IllegalArgumentException.class, () -> registry.execute(call, null));
        }
    }

    @Nested
    @DisplayName("MCP 工具注册")
    class McpToolRegistration {

        @Test
        void testRegisterMcpTool() {
            registry = new DefaultToolRegistry(List.of());
            registry.registerMcpTool("mcp_search", "Search tool",
                    Map.of("type", "object"), "search", "http://localhost:8080/mcp");

            assertEquals(1, registry.size());
            Tool tool = registry.get("mcp_search");
            assertNotNull(tool);
            assertEquals("mcp_search", tool.getName());
            assertEquals("mcp", tool.getDefinition().getSource());
            assertTrue(tool instanceof McpToolAdapter);
        }
    }

    @Nested
    @DisplayName("分类统计")
    class CategoryStats {

        @Test
        void testGetCategoryStats() {
            Tool t1 = createMockTool("t1", "builtin");
            Tool t2 = createMockTool("t2", "mcp");
            Tool t3 = createMockTool("t3", "builtin");
            registry = new DefaultToolRegistry(List.of(t1, t2, t3));

            Map<String, Long> stats = registry.getCategoryStats();
            assertEquals(2L, (long) stats.get("builtin"));
            assertEquals(1L, (long) stats.get("mcp"));
        }

        @Test
        void testGetCategoryStatsEmpty() {
            registry = new DefaultToolRegistry(List.of());
            Map<String, Long> stats = registry.getCategoryStats();
            assertTrue(stats.isEmpty());
        }
    }
}
