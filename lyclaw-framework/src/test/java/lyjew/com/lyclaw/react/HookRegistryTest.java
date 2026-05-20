package lyjew.com.lyclaw.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.tool.ToolRegistry;

/**
 * Comprehensive unit tests for {@link HookRegistry} and related classes
 * ({@link AgentFinalizeResult}, {@link AgentRuntimeType}).
 *
 * <p>Covers all dispatch methods, registration semantics, priority ordering,
 * exception isolation, constructor variants, and lifecycle management.
 * </p>
 */
@DisplayName("HookRegistry")
class HookRegistryTest {

    /**
     * All 29 hook names that the registry registers hooks under.
     */
    private static final String[] ALL_HOOK_NAMES = {
        "beforeRequest", "beforeModel", "afterModel", "wrapToolCall",
        "wrapToolExecutor", "afterResult",
        "beforeModelResolve", "modelCallStarted", "modelCallEnded",
        "llmInput", "llmOutput",
        "beforeAgentStart", "beforeAgentReply", "beforeAgentFinalize",
        "agentEnd", "beforeAgentRun",
        "beforeToolCall", "afterToolCall", "toolResultPersist",
        "sessionStart", "sessionEnd",
        "subagentSpawning", "subagentSpawned", "subagentEnded",
        "beforeCompaction", "afterCompaction",
        "messageReceived", "messageSending", "messageSent",
        "heartbeatPromptContribution"
    };

    // ── helper factories ──────────────────────────────────────────

    /** Creates a minimal AgentContext for testing. */
    private static AgentContext createMinimalContext() {
        return new AgentContext(
            "test-session",
            "test message",
            "system prompt",
            null,    // toolRegistry — not needed for dispatch tests
            null,    // method
            null     // args
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // HookRegistry tests
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("noArg constructor creates empty registry")
        void noArgConstructorCreatesEmptyRegistry() {
            HookRegistry registry = new HookRegistry();

            assertThat(registry.getHookNames()).isEmpty();
            assertThat(registry.getTotalRegistrations()).isEqualTo(0);
            assertThat(registry.getAllHooks()).isEmpty();
        }

        @Test
        @DisplayName("constructor with List registers all provided hooks")
        void constructorWithListRegistersAllHooks() {
            SpyHook hook1 = new SpyHook();
            SpyHook hook2 = new SpyHook();
            List<AgentHook> hooks = List.of(hook1, hook2);

            HookRegistry registry = new HookRegistry(hooks);

            assertThat(registry.getHookNames()).contains(ALL_HOOK_NAMES);
            // 2 hooks * 29 names = 58
            assertThat(registry.getTotalRegistrations()).isEqualTo(2 * ALL_HOOK_NAMES.length);
            assertThat(registry.getAllHooks()).containsExactlyInAnyOrder(hook1, hook2);
        }

        @Test
        @DisplayName("constructor with null List does not throw")
        void constructorWithNullListDoesNotThrow() {
            assertThatCode(() -> new HookRegistry(null))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("constructor with List uses hook.getOrder() as priority")
        void constructorUsesHookOrderAsPriority() {
            SpyHook hook1 = new SpyHook() {
                @Override public int getOrder() { return 200; }
            };
            SpyHook hook2 = new SpyHook() {
                @Override public int getOrder() { return 50; }
            };
            HookRegistry registry = new HookRegistry(List.of(hook1, hook2));

            // Higher priority (200) should appear first
            List<AgentHook> hooks = registry.getHooks("beforeRequest");
            assertThat(hooks.get(0)).isSameAs(hook1);
            assertThat(hooks.get(1)).isSameAs(hook2);
        }
    }

    @Nested
    @DisplayName("Registration")
    class Registration {

        private HookRegistry registry;

        @BeforeEach
        void setUp() {
            registry = new HookRegistry();
        }

        @Test
        @DisplayName("register single hook appears in getHooks for all 29 names")
        void registerSingleHookAppearsInAllNames() {
            SpyHook hook = new SpyHook();
            registry.register(hook);

            assertThat(registry.getHookNames()).contains(ALL_HOOK_NAMES);
            for (String name : ALL_HOOK_NAMES) {
                assertThat(registry.getHooks(name))
                    .as("Hook should be registered under name: " + name)
                    .containsExactly(hook);
            }
        }

        @Test
        @DisplayName("register with custom source and priority stores correctly")
        void registerWithCustomSourceAndPriority() {
            SpyHook hook = new SpyHook();
            registry.register(hook, "custom-source", 300);

            assertThat(registry.getHookNames()).contains(ALL_HOOK_NAMES);
            for (String name : ALL_HOOK_NAMES) {
                assertThat(registry.getHooks(name)).containsExactly(hook);
            }
        }

        @Test
        @DisplayName("register null hook does nothing")
        void registerNullHookDoesNothing() {
            registry.register(null);
            assertThat(registry.getHookNames()).isEmpty();
            assertThat(registry.getTotalRegistrations()).isEqualTo(0);
        }

        @Test
        @DisplayName("getAllHooks returns all registered hooks, deduplicated")
        void getAllHooksReturnsDeduplicated() {
            SpyHook hook1 = new SpyHook();
            SpyHook hook2 = new SpyHook();
            SpyHook hook3 = new SpyHook();

            registry.register(hook1, "src1", 100);
            registry.register(hook2, "src2", 200);
            registry.register(hook3, "src3", 300);

            List<AgentHook> all = registry.getAllHooks();
            assertThat(all).containsExactlyInAnyOrder(hook1, hook2, hook3);
        }

        @Test
        @DisplayName("registering same hook twice — getAllHooks deduplicates by identity")
        void registeringSameHookTwiceDeduplicates() {
            SpyHook hook = new SpyHook();
            registry.register(hook, "src1", 100);
            registry.register(hook, "src2", 200);  // same hook instance, different priority

            // getAllHooks deduplicates by identity (LinkedHashSet)
            assertThat(registry.getAllHooks()).hasSize(1).containsExactly(hook);
            // But getTotalRegistrations counts all entries across all names
            assertThat(registry.getTotalRegistrations()).isEqualTo(2 * ALL_HOOK_NAMES.length);
        }
    }

    @Nested
    @DisplayName("Priority Ordering")
    class PriorityOrdering {

        private HookRegistry registry;

        @BeforeEach
        void setUp() {
            registry = new HookRegistry();
        }

        @Test
        @DisplayName("higher priority hooks appear first in getHooks list")
        void higherPriorityFirst() {
            SpyHook hookLow = new SpyHook();
            SpyHook hookMid = new SpyHook();
            SpyHook hookHigh = new SpyHook();

            registry.register(hookLow, "low", 50);
            registry.register(hookMid, "mid", 100);
            registry.register(hookHigh, "high", 200);

            List<AgentHook> hooks = registry.getHooks("beforeRequest");
            assertThat(hooks).containsExactly(hookHigh, hookMid, hookLow);
        }

        @Test
        @DisplayName("equal priority — first registered appears first (stable)")
        void equalPriorityStableOrder() {
            SpyHook hook1 = new SpyHook();
            SpyHook hook2 = new SpyHook();

            registry.register(hook1, "src", 100);
            registry.register(hook2, "src", 100);

            // When priorities are equal, insertion order is preserved
            // because Java's TimSort is stable
            List<AgentHook> hooks = registry.getHooks("beforeRequest");
            assertThat(hooks.get(0)).isSameAs(hook1);
            assertThat(hooks.get(1)).isSameAs(hook2);
        }

        @Test
        @DisplayName("priority sort descending — hook1 prio=50, hook2 prio=200")
        void prioritySortDescending() {
            SpyHook hook1 = new SpyHook();
            SpyHook hook2 = new SpyHook();

            registry.register(hook1, "src1", 50);
            registry.register(hook2, "src2", 200);

            List<AgentHook> hooks = registry.getHooks("agentEnd");
            assertThat(hooks).containsExactly(hook2, hook1);
        }
    }

    @Nested
    @DisplayName("getHookNames")
    class GetHookNames {

        @Test
        @DisplayName("returns unmodifiable set")
        void returnsUnmodifiableSet() {
            HookRegistry registry = new HookRegistry();
            registry.register(new SpyHook());

            Set<String> names = registry.getHookNames();
            assertThatThrownBy(() -> names.add("extra"))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("getHooks")
    class GetHooks {

        private HookRegistry registry;

        @BeforeEach
        void setUp() {
            registry = new HookRegistry();
        }

        @Test
        @DisplayName("returns unmodifiable list")
        void returnsUnmodifiableList() {
            registry.register(new SpyHook());
            List<AgentHook> hooks = registry.getHooks("beforeRequest");

            assertThatThrownBy(() -> hooks.add(new SpyHook()))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("unknown hook name returns empty list")
        void unknownHookNameReturnsEmptyList() {
            List<AgentHook> hooks = registry.getHooks("nonexistentHook");
            assertThat(hooks).isEmpty();
        }
    }

    @Nested
    @DisplayName("getTotalRegistrations")
    class GetTotalRegistrations {

        @Test
        @DisplayName("3 hooks x 29 names = 87")
        void threeHooksTimes29Names() {
            HookRegistry registry = new HookRegistry();
            registry.register(new SpyHook(), "s1", 100);
            registry.register(new SpyHook(), "s2", 200);
            registry.register(new SpyHook(), "s3", 300);

            assertThat(registry.getTotalRegistrations()).isEqualTo(3 * ALL_HOOK_NAMES.length);
        }

        @Test
        @DisplayName("counts duplicates across names but same hook instance")
        void countsDuplicates() {
            HookRegistry registry = new HookRegistry();
            SpyHook hook = new SpyHook();
            registry.register(hook);

            // 1 hook registered under all 29 names
            assertThat(registry.getTotalRegistrations()).isEqualTo(ALL_HOOK_NAMES.length);
            // But getAllHooks deduplicates
            assertThat(registry.getAllHooks()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("clear")
    class Clear {

        @Test
        @DisplayName("after clear, getHookNames is empty")
        void afterClearHookNamesEmpty() {
            HookRegistry registry = new HookRegistry();
            registry.register(new SpyHook());
            registry.register(new SpyHook());

            assertThat(registry.getHookNames()).isNotEmpty();

            registry.clear();

            assertThat(registry.getHookNames()).isEmpty();
            assertThat(registry.getTotalRegistrations()).isEqualTo(0);
            assertThat(registry.getAllHooks()).isEmpty();
        }

        @Test
        @DisplayName("clear on already empty registry is safe")
        void clearOnEmptyRegistryIsSafe() {
            HookRegistry registry = new HookRegistry();
            assertThatCode(registry::clear).doesNotThrowAnyException();
            assertThat(registry.getHookNames()).isEmpty();
        }
    }

    // ── Dispatch methods ──────────────────────────────────────────

    @Nested
    @DisplayName("dispatchBeforeRequest")
    class DispatchBeforeRequest {

        private HookRegistry registry;
        private AgentContext ctx;

        @BeforeEach
        void setUp() {
            registry = new HookRegistry();
            ctx = createMinimalContext();
        }

        @Test
        @DisplayName("calls hook.beforeRequest with AgentContext")
        void callsHookBeforeRequest() {
            AtomicBoolean called = new AtomicBoolean(false);
            SpyHook hook = new SpyHook() {
                @Override public void beforeRequest(AgentContext c) {
                    called.set(true);
                    assertThat(c).isSameAs(ctx);
                }
            };
            registry.register(hook);
            registry.dispatchBeforeRequest(ctx);
            assertThat(called).isTrue();
        }

        @Test
        @DisplayName("no hooks registered does not throw")
        void noHooksDoesNotThrow() {
            assertThatCode(() -> registry.dispatchBeforeRequest(ctx))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("dispatchBeforeModel")
    class DispatchBeforeModel {

        private HookRegistry registry;
        private AgentContext ctx;
        private List<Message> originalMessages;

        @BeforeEach
        void setUp() {
            registry = new HookRegistry();
            ctx = createMinimalContext();
            originalMessages = List.of(Message.user("hello"));
        }

        @Test
        @DisplayName("hook receives messages list correctly")
        void hookReceivesMessagesCorrectly() {
            AtomicReference<List<Message>> captured = new AtomicReference<>();
            SpyHook hook = new SpyHook() {
                @Override public List<Message> beforeModel(List<Message> messages, AgentContext c) {
                    captured.set(messages);
                    return messages;
                }
            };
            registry.register(hook);

            List<Message> input = new ArrayList<>(originalMessages);
            registry.dispatchBeforeModel(input, ctx);

            // The hook receives the original list reference
            assertThat(captured.get()).isSameAs(input);
            assertThat(captured.get()).hasSize(1);
        }

        @Test
        @DisplayName("hook can return a modified list from beforeModel")
        void hookCanReturnModifiedList() {
            AtomicReference<List<Message>> returnedRef = new AtomicReference<>();
            SpyHook hook = new SpyHook() {
                @Override public List<Message> beforeModel(List<Message> messages, AgentContext c) {
                    List<Message> modified = new ArrayList<>(messages);
                    modified.add(Message.system("injected"));
                    returnedRef.set(modified);
                    return modified;
                }
            };
            registry.register(hook);

            registry.dispatchBeforeModel(new ArrayList<>(originalMessages), ctx);

            // The hook returned a different list (2 elements)
            assertThat(returnedRef.get()).hasSize(2);
            assertThat(returnedRef.get().get(1).getRole()).isEqualTo("system");
        }

        @Test
        @DisplayName("multiple hooks chain correctly in beforeModel")
        void multipleHooksChain() {
            AtomicReference<List<Message>> hook1Received = new AtomicReference<>();
            AtomicReference<List<Message>> hook2Received = new AtomicReference<>();

            SpyHook hook1 = new SpyHook() {
                @Override public List<Message> beforeModel(List<Message> messages, AgentContext c) {
                    hook1Received.set(messages);
                    List<Message> modified = new ArrayList<>(messages);
                    modified.add(Message.system("fromHook1"));
                    return modified;
                }
            };
            SpyHook hook2 = new SpyHook() {
                @Override public List<Message> beforeModel(List<Message> messages, AgentContext c) {
                    hook2Received.set(messages);
                    return messages;
                }
            };
            registry.register(hook1, "s1", 200);  // higher priority, fires first
            registry.register(hook2, "s2", 100);

            registry.dispatchBeforeModel(new ArrayList<>(originalMessages), ctx);

            // hook1 receives original list (1 element)
            assertThat(hook1Received.get()).hasSize(1);
            // hook2 receives hook1's returned list (2 elements: original + injected)
            assertThat(hook2Received.get()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("dispatchAfterModel")
    class DispatchAfterModel {

        private HookRegistry registry;
        private AgentContext ctx;

        @BeforeEach
        void setUp() {
            registry = new HookRegistry();
            ctx = createMinimalContext();
        }

        @Test
        @DisplayName("hook can transform response string")
        void hookTransformsResponse() {
            SpyHook hook = new SpyHook() {
                @Override public String afterModel(String response, AgentContext c) {
                    return "[WRAPPED] " + response + " [END]";
                }
            };
            registry.register(hook);

            String result = registry.dispatchAfterModel("original", ctx);
            assertThat(result).isEqualTo("[WRAPPED] original [END]");
        }

        @Test
        @DisplayName("chains multiple transformations by priority")
        void chainsMultipleTransformations() {
            SpyHook hook1 = new SpyHook() {
                @Override public String afterModel(String response, AgentContext c) {
                    return "A:" + response;
                }
            };
            SpyHook hook2 = new SpyHook() {
                @Override public String afterModel(String response, AgentContext c) {
                    return response + ":Z";
                }
            };
            registry.register(hook1, "src1", 200);  // higher priority, runs first
            registry.register(hook2, "src2", 100);

            String result = registry.dispatchAfterModel("hello", ctx);
            // hook1 runs first (prio 200) -> "A:hello"
            // hook2 runs second (prio 100) -> "A:hello:Z"
            assertThat(result).isEqualTo("A:hello:Z");
        }
    }

    @Nested
    @DisplayName("dispatchWrapToolCall")
    class DispatchWrapToolCall {

        private HookRegistry registry;
        private AgentContext ctx;

        @BeforeEach
        void setUp() {
            registry = new HookRegistry();
            ctx = createMinimalContext();
        }

        @Test
        @DisplayName("hook can wrap tool call")
        void hookWrapsToolCall() {
            ToolCall original = ToolCall.builder()
                .toolCallId("call-123")
                .name("test_tool")
                .arguments("{}")
                .build();

            SpyHook hook = new SpyHook() {
                @Override public ToolCall wrapToolCall(ToolCall tc, AgentContext c) {
                    tc.setName("wrapped_" + tc.getName());
                    return tc;
                }
            };
            registry.register(hook);

            ToolCall result = registry.dispatchWrapToolCall(original, ctx);
            assertThat(result.getName()).isEqualTo("wrapped_test_tool");
        }

        @Test
        @DisplayName("wrapping pass-through when no hooks registered")
        void wrapPassThrough() {
            ToolCall original = ToolCall.builder()
                .toolCallId("call-1")
                .name("tool")
                .arguments("{}")
                .build();

            ToolCall result = registry.dispatchWrapToolCall(original, ctx);
            assertThat(result).isSameAs(original);
        }

        @Test
        @DisplayName("chains multiple tool call wrappers")
        void chainsMultipleWrappers() {
            ToolCall original = ToolCall.builder()
                .toolCallId("call-1")
                .name("original_tool")
                .arguments("{}")
                .build();

            SpyHook hook1 = new SpyHook() {
                @Override public ToolCall wrapToolCall(ToolCall tc, AgentContext c) {
                    tc.setName("wrapped1_" + tc.getName());
                    return tc;
                }
            };
            SpyHook hook2 = new SpyHook() {
                @Override public ToolCall wrapToolCall(ToolCall tc, AgentContext c) {
                    tc.setName(tc.getName() + "_wrapped2");
                    return tc;
                }
            };
            registry.register(hook1, "s1", 200);  // higher priority, runs first
            registry.register(hook2, "s2", 100);

            ToolCall result = registry.dispatchWrapToolCall(original, ctx);
            assertThat(result.getName()).isEqualTo("wrapped1_original_tool_wrapped2");
        }
    }

    @Nested
    @DisplayName("dispatchWrapToolExecutor")
    class DispatchWrapToolExecutor {

        private HookRegistry registry;
        private AgentContext ctx;

        @BeforeEach
        void setUp() {
            registry = new HookRegistry();
            ctx = createMinimalContext();
        }

        @Test
        @DisplayName("hook can wrap ToolExecutor")
        void hookWrapsToolExecutor() {
            ToolExecutor original = (name, id, args) -> "result:" + name;
            AtomicBoolean wrapped = new AtomicBoolean(false);

            SpyHook hook = new SpyHook() {
                @Override public ToolExecutor wrapToolExecutor(ToolExecutor inner, AgentContext c) {
                    wrapped.set(true);
                    return (toolName, toolCallId, argumentsJson) ->
                        "[WRAP]" + inner.execute(toolName, toolCallId, argumentsJson);
                }
            };
            registry.register(hook);

            ToolExecutor result = registry.dispatchWrapToolExecutor(original, ctx);
            assertThat(wrapped).isTrue();
            assertThat(result.execute("t", "id", "{}")).isEqualTo("[WRAP]result:t");
        }

        @Test
        @DisplayName("pass-through with no hooks")
        void passThroughNoHooks() {
            ToolExecutor original = (name, id, args) -> name;
            ToolExecutor result = registry.dispatchWrapToolExecutor(original, ctx);
            assertThat(result).isSameAs(original);
        }

        @Test
        @DisplayName("chains multiple ToolExecutor wrappers")
        void chainsMultipleWrappers() {
            ToolExecutor original = (name, id, args) -> name;

            SpyHook hook1 = new SpyHook() {
                @Override public ToolExecutor wrapToolExecutor(ToolExecutor inner, AgentContext c) {
                    return (n, i, a) -> "[" + inner.execute(n, i, a) + "]";
                }
            };
            SpyHook hook2 = new SpyHook() {
                @Override public ToolExecutor wrapToolExecutor(ToolExecutor inner, AgentContext c) {
                    return (n, i, a) -> "<<" + inner.execute(n, i, a) + ">>";
                }
            };
            registry.register(hook1, "s1", 200);  // higher priority, runs first
            registry.register(hook2, "s2", 100);

            ToolExecutor result = registry.dispatchWrapToolExecutor(original, ctx);
            assertThat(result.execute("X", "id", "{}")).isEqualTo("<<[X]>>");
        }
    }

    @Nested
    @DisplayName("dispatchAfterResult")
    class DispatchAfterResult {

        @Test
        @DisplayName("hook can transform result string")
        void hookTransformsResult() {
            HookRegistry registry = new HookRegistry();
            AgentContext ctx = createMinimalContext();
            SpyHook hook = new SpyHook() {
                @Override public String afterResult(String result, AgentContext c) {
                    return "RESULT: " + result;
                }
            };
            registry.register(hook);

            String result = registry.dispatchAfterResult("done", ctx);
            assertThat(result).isEqualTo("RESULT: done");
        }
    }

    @Nested
    @DisplayName("dispatchBeforeModelResolve")
    class DispatchBeforeModelResolve {

        @Test
        @DisplayName("calls hook.beforeModelResolve")
        void callsHook() {
            HookRegistry registry = new HookRegistry();
            AgentContext ctx = createMinimalContext();
            AtomicBoolean called = new AtomicBoolean(false);

            SpyHook hook = new SpyHook() {
                @Override public void beforeModelResolve(AgentContext c) {
                    called.set(true);
                    assertThat(c).isSameAs(ctx);
                }
            };
            registry.register(hook);
            registry.dispatchBeforeModelResolve(ctx);
            assertThat(called).isTrue();
        }
    }

    @Nested
    @DisplayName("dispatchModelCallStarted")
    class DispatchModelCallStarted {

        @Test
        @DisplayName("calls hook.modelCallStarted")
        void callsHook() {
            HookRegistry registry = new HookRegistry();
            AgentContext ctx = createMinimalContext();
            AtomicBoolean called = new AtomicBoolean(false);

            SpyHook hook = new SpyHook() {
                @Override public void modelCallStarted(AgentContext c) {
                    called.set(true);
                }
            };
            registry.register(hook);
            registry.dispatchModelCallStarted(ctx);
            assertThat(called).isTrue();
        }
    }

    @Nested
    @DisplayName("dispatchModelCallEnded")
    class DispatchModelCallEnded {

        @Test
        @DisplayName("calls hook.modelCallEnded")
        void callsHook() {
            HookRegistry registry = new HookRegistry();
            AgentContext ctx = createMinimalContext();
            AtomicBoolean called = new AtomicBoolean(false);

            SpyHook hook = new SpyHook() {
                @Override public void modelCallEnded(AgentContext c) {
                    called.set(true);
                }
            };
            registry.register(hook);
            registry.dispatchModelCallEnded(ctx);
            assertThat(called).isTrue();
        }
    }

    @Nested
    @DisplayName("dispatchBeforeToolCall")
    class DispatchBeforeToolCall {

        @Test
        @DisplayName("passes correct toolName, toolCallId, args, and ctx to hook")
        void passesCorrectArgs() {
            HookRegistry registry = new HookRegistry();
            AgentContext ctx = createMinimalContext();
            AtomicReference<String> capturedToolName = new AtomicReference<>();
            AtomicReference<String> capturedId = new AtomicReference<>();
            AtomicReference<String> capturedArgs = new AtomicReference<>();

            SpyHook hook = new SpyHook() {
                @Override
                public void beforeToolCall(String toolName, String toolCallId,
                                           String args, AgentContext c) {
                    capturedToolName.set(toolName);
                    capturedId.set(toolCallId);
                    capturedArgs.set(args);
                    assertThat(c).isSameAs(ctx);
                }
            };
            registry.register(hook);
            registry.dispatchBeforeToolCall("my_tool", "call-abc", "{\"x\":1}", ctx);

            assertThat(capturedToolName.get()).isEqualTo("my_tool");
            assertThat(capturedId.get()).isEqualTo("call-abc");
            assertThat(capturedArgs.get()).isEqualTo("{\"x\":1}");
        }
    }

    @Nested
    @DisplayName("dispatchAfterToolCall")
    class DispatchAfterToolCall {

        @Test
        @DisplayName("passes correct toolName, toolCallId, result, and ctx")
        void passesCorrectArgs() {
            HookRegistry registry = new HookRegistry();
            AgentContext ctx = createMinimalContext();
            AtomicReference<String> capturedResult = new AtomicReference<>();

            SpyHook hook = new SpyHook() {
                @Override
                public void afterToolCall(String toolName, String toolCallId,
                                          String result, AgentContext c) {
                    capturedResult.set(result);
                }
            };
            registry.register(hook);
            registry.dispatchAfterToolCall("tool1", "id1", "all done", ctx);

            assertThat(capturedResult.get()).isEqualTo("all done");
        }
    }

    @Nested
    @DisplayName("dispatchBeforeAgentFinalize")
    class DispatchBeforeAgentFinalize {

        private HookRegistry registry;
        private AgentContext ctx;

        @BeforeEach
        void setUp() {
            registry = new HookRegistry();
            ctx = createMinimalContext();
        }

        @Test
        @DisplayName("all hooks return CONTINUE -> returns CONTINUE")
        void allContinueReturnsContinue() {
            SpyHook hook1 = new SpyHook();
            SpyHook hook2 = new SpyHook();
            registry.register(hook1);
            registry.register(hook2);

            AgentFinalizeResult result = registry.dispatchBeforeAgentFinalize(ctx);
            assertThat(result.isContinue()).isTrue();
            assertThat(result.getAction()).isEqualTo(AgentFinalizeResult.Action.CONTINUE);
        }

        @Test
        @DisplayName("first REVISE short-circuits dispatch")
        void firstReviseShortCircuits() {
            AtomicBoolean secondCalled = new AtomicBoolean(false);

            SpyHook hook1 = new SpyHook() {
                @Override
                public AgentFinalizeResult beforeAgentFinalize(AgentContext c) {
                    return AgentFinalizeResult.revise("needs work", "fix it");
                }
            };
            SpyHook hook2 = new SpyHook() {
                @Override
                public AgentFinalizeResult beforeAgentFinalize(AgentContext c) {
                    secondCalled.set(true);
                    return AgentFinalizeResult.continue_();
                }
            };
            registry.register(hook1, "src1", 200);  // higher priority runs first
            registry.register(hook2, "src2", 100);

            AgentFinalizeResult result = registry.dispatchBeforeAgentFinalize(ctx);
            assertThat(result.isRevise()).isTrue();
            assertThat(result.getReason()).isEqualTo("needs work");
            assertThat(result.getRetryInstruction()).isEqualTo("fix it");
            assertThat(secondCalled.get()).isFalse();  // short-circuited
        }

        @Test
        @DisplayName("first FINALIZE short-circuits dispatch")
        void firstFinalizeShortCircuits() {
            AtomicBoolean secondCalled = new AtomicBoolean(false);

            SpyHook hook1 = new SpyHook() {
                @Override
                public AgentFinalizeResult beforeAgentFinalize(AgentContext c) {
                    return AgentFinalizeResult.finalize("all done");
                }
            };
            SpyHook hook2 = new SpyHook() {
                @Override
                public AgentFinalizeResult beforeAgentFinalize(AgentContext c) {
                    secondCalled.set(true);
                    return AgentFinalizeResult.continue_();
                }
            };
            registry.register(hook1, "src1", 300);
            registry.register(hook2, "src2", 200);

            AgentFinalizeResult result = registry.dispatchBeforeAgentFinalize(ctx);
            assertThat(result.isFinalize()).isTrue();
            assertThat(result.getReason()).isEqualTo("all done");
            assertThat(secondCalled.get()).isFalse();
        }

        @Test
        @DisplayName("returns CONTINUE when no hooks registered")
        void noHooksReturnsContinue() {
            AgentFinalizeResult result = registry.dispatchBeforeAgentFinalize(ctx);
            assertThat(result.isContinue()).isTrue();
        }

        @Test
        @DisplayName("null result from hook is treated as continue")
        void nullResultTreatedAsContinue() {
            SpyHook returningNull = new SpyHook() {
                @Override
                public AgentFinalizeResult beforeAgentFinalize(AgentContext c) {
                    return null;
                }
            };
            SpyHook returningContinue = new SpyHook() {
                @Override
                public AgentFinalizeResult beforeAgentFinalize(AgentContext c) {
                    return AgentFinalizeResult.continue_();
                }
            };
            registry.register(returningNull, "src1", 200);
            registry.register(returningContinue, "src2", 100);

            AgentFinalizeResult result = registry.dispatchBeforeAgentFinalize(ctx);
            assertThat(result.isContinue()).isTrue();
        }
    }

    @Nested
    @DisplayName("dispatchAgentEnd")
    class DispatchAgentEnd {

        @Test
        @DisplayName("calls hook.agentEnd")
        void callsAgentEnd() {
            HookRegistry registry = new HookRegistry();
            AgentContext ctx = createMinimalContext();
            AtomicBoolean called = new AtomicBoolean(false);

            SpyHook hook = new SpyHook() {
                @Override public void agentEnd(AgentContext c) {
                    called.set(true);
                }
            };
            registry.register(hook);
            registry.dispatchAgentEnd(ctx);
            assertThat(called).isTrue();
        }
    }

    @Nested
    @DisplayName("dispatchBeforeAgentRun")
    class DispatchBeforeAgentRun {

        @Test
        @DisplayName("calls hook.beforeAgentRun")
        void callsHook() {
            HookRegistry registry = new HookRegistry();
            AgentContext ctx = createMinimalContext();
            AtomicBoolean called = new AtomicBoolean(false);

            SpyHook hook = new SpyHook() {
                @Override public void beforeAgentRun(AgentContext c) {
                    called.set(true);
                }
            };
            registry.register(hook);
            registry.dispatchBeforeAgentRun(ctx);
            assertThat(called).isTrue();
        }
    }

    // ── Exception isolation ──────────────────────────────────────

    @Nested
    @DisplayName("Exception Isolation")
    class ExceptionIsolation {

        @Test
        @DisplayName("hook exception does not break dispatch — other hooks still fire")
        void hookExceptionDoesNotBreakDispatch() {
            HookRegistry registry = new HookRegistry();
            AgentContext ctx = createMinimalContext();
            AtomicBoolean secondFired = new AtomicBoolean(false);

            SpyHook throwingHook = new SpyHook() {
                @Override public void beforeRequest(AgentContext c) {
                    throw new RuntimeException("simulated failure");
                }
            };
            SpyHook goodHook = new SpyHook() {
                @Override public void beforeRequest(AgentContext c) {
                    secondFired.set(true);
                }
            };
            registry.register(throwingHook, "bad", 200);
            registry.register(goodHook, "good", 100);

            assertThatCode(() -> registry.dispatchBeforeRequest(ctx))
                .doesNotThrowAnyException();
            assertThat(secondFired.get()).isTrue();
        }

        @Test
        @DisplayName("exception in dispatchAfterModel — remaining hooks still fire")
        void exceptionInAfterModel() {
            HookRegistry registry = new HookRegistry();
            AgentContext ctx = createMinimalContext();
            AtomicBoolean secondFired = new AtomicBoolean(false);

            SpyHook throwingHook = new SpyHook() {
                @Override public String afterModel(String response, AgentContext c) {
                    throw new RuntimeException("boom");
                }
            };
            SpyHook goodHook = new SpyHook() {
                @Override public String afterModel(String response, AgentContext c) {
                    secondFired.set(true);
                    return response;
                }
            };
            registry.register(throwingHook, "bad", 200);
            registry.register(goodHook, "good", 100);

            String result = registry.dispatchAfterModel("hello", ctx);
            assertThat(result).isEqualTo("hello");  // original value preserved after exception
            assertThat(secondFired.get()).isTrue();
        }

        @Test
        @DisplayName("exception in dispatchBeforeAgentFinalize — remaining hooks still fire")
        void exceptionInBeforeAgentFinalize() {
            HookRegistry registry = new HookRegistry();
            AgentContext ctx = createMinimalContext();
            AtomicBoolean secondFired = new AtomicBoolean(false);

            SpyHook throwingHook = new SpyHook() {
                @Override public AgentFinalizeResult beforeAgentFinalize(AgentContext c) {
                    throw new RuntimeException("finalize explosion");
                }
            };
            SpyHook goodHook = new SpyHook() {
                @Override public AgentFinalizeResult beforeAgentFinalize(AgentContext c) {
                    secondFired.set(true);
                    return AgentFinalizeResult.continue_();
                }
            };
            registry.register(throwingHook, "bad", 300);
            registry.register(goodHook, "good", 200);

            AgentFinalizeResult result = registry.dispatchBeforeAgentFinalize(ctx);
            assertThat(result.isContinue()).isTrue();
            assertThat(secondFired.get()).isTrue();
        }

        @Test
        @DisplayName("exception in dispatchWrapToolCall does not lose original ToolCall")
        void exceptionInWrapToolCall() {
            HookRegistry registry = new HookRegistry();
            AgentContext ctx = createMinimalContext();

            ToolCall original = ToolCall.builder()
                .toolCallId("c1")
                .name("my_tool")
                .arguments("{}")
                .build();

            SpyHook throwingHook = new SpyHook() {
                @Override public ToolCall wrapToolCall(ToolCall tc, AgentContext c) {
                    throw new RuntimeException("wrap exploded");
                }
            };
            registry.register(throwingHook, "bad", 200);

            ToolCall result = registry.dispatchWrapToolCall(original, ctx);
            assertThat(result).isSameAs(original);
            assertThat(result.getName()).isEqualTo("my_tool");
        }
    }

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafety {

        @Test
        @DisplayName("concurrent register and dispatch does not corrupt state")
        void concurrentRegisterAndDispatch() throws Exception {
            HookRegistry registry = new HookRegistry();
            AgentContext ctx = createMinimalContext();
            AtomicInteger errors = new AtomicInteger(0);

            Thread registerThread = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    try {
                        registry.register(new SpyHook(), "t" + i, i);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
            });

            Thread dispatchThread = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    try {
                        registry.dispatchBeforeRequest(ctx);
                        registry.dispatchAfterModel("test", ctx);
                        registry.dispatchBeforeAgentFinalize(ctx);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
            });

            registerThread.start();
            dispatchThread.start();
            registerThread.join();
            dispatchThread.join();

            assertThat(errors.get()).isEqualTo(0);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Sequential Execution Order
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Sequential Execution Order")
    class SequentialExecutionOrder {

        @Test
        @DisplayName("dispatchAfterModel chains in priority order")
        void afterModelChainsInPriorityOrder() {
            HookRegistry registry = new HookRegistry();
            AgentContext ctx = createMinimalContext();
            List<String> callOrder = new CopyOnWriteArrayList<>();

            SpyHook hook1 = new SpyHook() {
                @Override public String afterModel(String response, AgentContext c) {
                    callOrder.add("hook1");
                    return response + "-X";
                }
            };
            SpyHook hook2 = new SpyHook() {
                @Override public String afterModel(String response, AgentContext c) {
                    callOrder.add("hook2");
                    return response + "-Y";
                }
            };
            SpyHook hook3 = new SpyHook() {
                @Override public String afterModel(String response, AgentContext c) {
                    callOrder.add("hook3");
                    return response + "-Z";
                }
            };

            registry.register(hook1, "s1", 300);  // highest priority, fires first
            registry.register(hook2, "s2", 200);
            registry.register(hook3, "s3", 100);  // lowest priority, fires last

            String result = registry.dispatchAfterModel("base", ctx);
            assertThat(callOrder).containsExactly("hook1", "hook2", "hook3");
            assertThat(result).isEqualTo("base-X-Y-Z");
        }

        @Test
        @DisplayName("dispatchBeforeRequest fires hooks in priority order")
        void beforeRequestFiresInPriorityOrder() {
            HookRegistry registry = new HookRegistry();
            AgentContext ctx = createMinimalContext();
            List<String> callOrder = new CopyOnWriteArrayList<>();

            SpyHook hook1 = new SpyHook() {
                @Override public void beforeRequest(AgentContext c) { callOrder.add("h1"); }
            };
            SpyHook hook2 = new SpyHook() {
                @Override public void beforeRequest(AgentContext c) { callOrder.add("h2"); }
            };
            SpyHook hook3 = new SpyHook() {
                @Override public void beforeRequest(AgentContext c) { callOrder.add("h3"); }
            };

            registry.register(hook1, "s1", 10);
            registry.register(hook2, "s2", 20);
            registry.register(hook3, "s3", 30);   // highest priority

            registry.dispatchBeforeRequest(ctx);
            assertThat(callOrder).containsExactly("h3", "h2", "h1");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // AgentFinalizeResult tests
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AgentFinalizeResult")
    class AgentFinalizeResultTests {

        @Test
        @DisplayName("continue_() returns action=CONTINUE")
        void continueReturnsContinue() {
            AgentFinalizeResult result = AgentFinalizeResult.continue_();
            assertThat(result.getAction()).isEqualTo(AgentFinalizeResult.Action.CONTINUE);
            assertThat(result.isContinue()).isTrue();
            assertThat(result.isRevise()).isFalse();
            assertThat(result.isFinalize()).isFalse();
        }

        @Test
        @DisplayName("revise(reason, instruction) returns action=REVISE with reason and instruction")
        void reviseWithReasonAndInstruction() {
            AgentFinalizeResult result = AgentFinalizeResult.revise("bad output", "try harder");

            assertThat(result.getAction()).isEqualTo(AgentFinalizeResult.Action.REVISE);
            assertThat(result.isRevise()).isTrue();
            assertThat(result.isContinue()).isFalse();
            assertThat(result.isFinalize()).isFalse();
            assertThat(result.getReason()).isEqualTo("bad output");
            assertThat(result.getRetryInstruction()).isEqualTo("try harder");
        }

        @Test
        @DisplayName("revise with idempotencyKey and maxAttempts")
        void reviseWithIdempotencyKey() {
            AgentFinalizeResult result = AgentFinalizeResult.revise(
                "retry", "do again", "key-42", 3);

            assertThat(result.getAction()).isEqualTo(AgentFinalizeResult.Action.REVISE);
            assertThat(result.getReason()).isEqualTo("retry");
            assertThat(result.getRetryInstruction()).isEqualTo("do again");
            assertThat(result.getIdempotencyKey()).isEqualTo("key-42");
            assertThat(result.getMaxAttempts()).isEqualTo(3);
        }

        @Test
        @DisplayName("finalize(reason) returns action=FINALIZE with reason")
        void finalizeWithReason() {
            AgentFinalizeResult result = AgentFinalizeResult.finalize("task complete");

            assertThat(result.getAction()).isEqualTo(AgentFinalizeResult.Action.FINALIZE);
            assertThat(result.isFinalize()).isTrue();
            assertThat(result.isContinue()).isFalse();
            assertThat(result.isRevise()).isFalse();
            assertThat(result.getReason()).isEqualTo("task complete");
        }

        @Test
        @DisplayName("continue_() has null reason and retryInstruction")
        void continueHasNullFields() {
            AgentFinalizeResult result = AgentFinalizeResult.continue_();
            assertThat(result.getReason()).isNull();
            assertThat(result.getRetryInstruction()).isNull();
            assertThat(result.getIdempotencyKey()).isNull();
            assertThat(result.getMaxAttempts()).isZero();
        }

        @Test
        @DisplayName("finalize has null retryInstruction")
        void finalizeHasNullRetryInstruction() {
            AgentFinalizeResult result = AgentFinalizeResult.finalize("done");
            assertThat(result.getReason()).isEqualTo("done");
            assertThat(result.getRetryInstruction()).isNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // AgentRuntimeType tests
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AgentRuntimeType")
    class AgentRuntimeTypeTests {

        @Test
        @DisplayName("EMBEDDED enum value exists")
        void embeddedExists() {
            assertThat(AgentRuntimeType.valueOf("EMBEDDED")).isEqualTo(AgentRuntimeType.EMBEDDED);
        }

        @Test
        @DisplayName("ACP enum value exists")
        void acpExists() {
            assertThat(AgentRuntimeType.valueOf("ACP")).isEqualTo(AgentRuntimeType.ACP);
        }

        @Test
        @DisplayName("two distinct enum values")
        void twoDistinctValues() {
            assertThat(AgentRuntimeType.values())
                .containsExactly(AgentRuntimeType.EMBEDDED, AgentRuntimeType.ACP);
        }

        @Test
        @DisplayName("EMBEDDED is not equal to ACP")
        void embeddedNotEqualToAcp() {
            assertThat(AgentRuntimeType.EMBEDDED).isNotEqualTo(AgentRuntimeType.ACP);
        }

        @Test
        @DisplayName("AgentContext default runtime type is EMBEDDED")
        void agentContextDefaultRuntimeIsEmbedded() {
            AgentContext ctx = createMinimalContext();
            assertThat(ctx.getRuntimeType()).isEqualTo(AgentRuntimeType.EMBEDDED);
        }

        @Test
        @DisplayName("AgentContext runtime type can be set to ACP")
        void agentContextRuntimeCanBeSetToAcp() {
            AgentContext ctx = createMinimalContext();
            ctx.setRuntimeType(AgentRuntimeType.ACP);
            assertThat(ctx.getRuntimeType()).isEqualTo(AgentRuntimeType.ACP);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper: SpyHook
    // ═══════════════════════════════════════════════════════════════

    /**
     * A minimal implementation of {@link AgentHook} that records invocation counts
     * and can be overridden per test method for specific assertions.
     *
     * <p>All 30 default methods are inherited from AgentHook with their default
     * behavior; subclasses override only the methods they need.
     * </p>
     */
    static class SpyHook implements AgentHook {
        // All 30 methods use default implementations from AgentHook.
        // Tests override only the specific methods they want to verify.
    }
}
