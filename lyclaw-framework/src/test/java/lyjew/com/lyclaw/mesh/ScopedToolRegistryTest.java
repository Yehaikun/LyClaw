package lyjew.com.lyclaw.mesh;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.tool.ToolProvider;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 Per-Agent 工具作用域：
 * - GLOBAL：工具在全局注册表中可用
 * - PRIVATE：工具仅对当前 Agent 可见
 * - 重名覆盖：私有工具覆盖全局同名工具
 * - INHERIT：继承父 Agent 工具
 */
class ScopedToolRegistryTest {

    @Test
    void globalScopeShouldDelegateToGlobalRegistry() {
        MockGlobalRegistry global = new MockGlobalRegistry();
        global.addTool("global-tool", ToolDefinition.builder().name("global-tool").build());

        ScopedToolRegistry scoped = new ScopedToolRegistry(
                global, "test-agent", ToolScope.GLOBAL, List.of(), null);

        List<ToolDefinition> defs = scoped.getAllDefinitions();
        assertEquals(1, defs.size());
        assertEquals("global-tool", defs.get(0).getName());
    }

    @Test
    void privateScopeShouldIncludePrivateTools() {
        MockGlobalRegistry global = new MockGlobalRegistry();
        global.addTool("global-tool", ToolDefinition.builder().name("global-tool").build());

        ToolDefinition privateDef = ToolDefinition.builder()
                .name("secret-tool").description("Only for this agent").build();

        ScopedToolRegistry scoped = new ScopedToolRegistry(
                global, "test-agent", ToolScope.PRIVATE, List.of(privateDef), null);

        List<ToolDefinition> defs = scoped.getAllDefinitions();
        assertEquals(2, defs.size());
        assertTrue(defs.stream().anyMatch(d -> d.getName().equals("global-tool")));
        assertTrue(defs.stream().anyMatch(d -> d.getName().equals("secret-tool")));
    }

    @Test
    void privateToolShouldOverrideGlobalTool() {
        MockGlobalRegistry global = new MockGlobalRegistry();
        global.addTool("shared-tool", ToolDefinition.builder().name("shared-tool").description("global version").build());

        ToolDefinition privateDef = ToolDefinition.builder()
                .name("shared-tool").description("private version").build();

        ScopedToolRegistry scoped = new ScopedToolRegistry(
                global, "test-agent", ToolScope.PRIVATE, List.of(privateDef), null);

        List<ToolDefinition> defs = scoped.getAllDefinitions();
        // 只应该出现一次 "shared-tool"
        assertEquals(1, defs.stream().filter(d -> d.getName().equals("shared-tool")).count());
    }

    @Test
    void shouldRegisterPrivateTools() {
        MockGlobalRegistry global = new MockGlobalRegistry();
        ScopedToolRegistry scoped = new ScopedToolRegistry(
                global, "test-agent", ToolScope.PRIVATE, List.of(), null);

        Tool myTool = new Tool() {
            @Override public String getName() { return "my-private-tool"; }
            @Override public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("my-private-tool").build();
            }
            @Override public ToolExecutionResult execute(ToolCall tc, ChatContext ctx) {
                return ToolExecutionResult.success("private result", "my-private-tool");
            }
        };

        scoped.register(myTool);

        // 应该在私有列表中，不在全局
        assertNull(global.get("my-private-tool"));
        assertNotNull(scoped.get("my-private-tool"));
        assertTrue(scoped.getAllDefinitions().stream()
                .anyMatch(d -> d.getName().equals("my-private-tool")));
    }

    @Test
    void globalScopeShouldRegisterGlobally() {
        MockGlobalRegistry global = new MockGlobalRegistry();
        ScopedToolRegistry scoped = new ScopedToolRegistry(
                global, "test-agent", ToolScope.GLOBAL, List.of(), null);

        Tool myTool = new Tool() {
            @Override public String getName() { return "new-global-tool"; }
            @Override public ToolDefinition getDefinition() {
                return ToolDefinition.builder().name("new-global-tool").build();
            }
            @Override public ToolExecutionResult execute(ToolCall tc, ChatContext ctx) {
                return ToolExecutionResult.success("ok", "new-global-tool");
            }
        };

        scoped.register(myTool);
        assertNotNull(global.get("new-global-tool"));
    }

    @Test
    void shouldHandleInheritScope() {
        MockGlobalRegistry global = new MockGlobalRegistry();
        global.addTool("parent-tool", ToolDefinition.builder().name("parent-tool").build());

        ToolDefinition childPrivate = ToolDefinition.builder().name("child-tool").build();

        ScopedToolRegistry scoped = new ScopedToolRegistry(
                global, "child-agent", ToolScope.INHERIT, List.of(childPrivate), "parent-agent");

        List<ToolDefinition> defs = scoped.getAllDefinitions();
        assertTrue(defs.stream().anyMatch(d -> d.getName().equals("parent-tool")));
        assertTrue(defs.stream().anyMatch(d -> d.getName().equals("child-tool")));
    }

    @Test
    void shouldReportPrivateToolCount() {
        ToolDefinition t1 = ToolDefinition.builder().name("tool-1").build();
        ToolDefinition t2 = ToolDefinition.builder().name("tool-2").build();

        ScopedToolRegistry scoped = new ScopedToolRegistry(
                null, "agent", ToolScope.PRIVATE, List.of(t1, t2), null);

        assertEquals(2, scoped.privateToolCount());
    }

    // ── Mock 全局注册表 ──

    static class MockGlobalRegistry implements ToolRegistry {
        private final java.util.concurrent.ConcurrentHashMap<String, Tool> tools = new java.util.concurrent.ConcurrentHashMap<>();

        void addTool(String name, ToolDefinition def) {
            tools.put(name, new Tool() {
                @Override public String getName() { return name; }
                @Override public ToolDefinition getDefinition() { return def; }
                @Override public ToolExecutionResult execute(ToolCall tc, ChatContext ctx) {
                    return ToolExecutionResult.success("ok", name);
                }
            });
        }

        @Override public void register(Tool tool) { tools.put(tool.getName(), tool); }
        @Override public Tool get(String name) { return tools.get(name); }
        @Override public List<ToolDefinition> getAllDefinitions() {
            return tools.values().stream().map(Tool::getDefinition).toList();
        }
        @Override public ToolExecutionResult execute(ToolCall tc, ChatContext ctx) {
            Tool tool = tools.get(tc.getName());
            return tool != null ? tool.execute(tc, ctx) : ToolExecutionResult.failure("not found", tc.getName());
        }
    }
}
