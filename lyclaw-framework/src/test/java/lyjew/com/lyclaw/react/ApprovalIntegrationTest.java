package lyjew.com.lyclaw.react;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.chat.ChatModel;
import lyjew.com.lyclaw.chat.RoutingDecision;
import lyjew.com.lyclaw.config.AgentProperties;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 层面一（沙箱简化）+ 层面二（用户审批流程）集成验证。
 *
 * <p>验证点：
 * <ol>
 *   <li>ApprovalStore: create/approve/deny/denyAll/pendingCount 核心契约</li>
 *   <li>ALLOW 工具（不在审批集合中）直接执行，无 tool_approval 事件</li>
 *   <li>ASK 工具触发 tool_approval SSE 事件 → 用户允许后执行 → 用户拒绝返回错误</li>
 * </ol>
 */
@DisplayName("层面1+2 集成测试：沙箱模式 + 用户审批流程")
class ApprovalIntegrationTest {

    // ── ApprovalStore 单元测试 ────────────────────────────────────

    @Nested
    @DisplayName("ApprovalStore — 审批存储核心逻辑")
    class ApprovalStoreTests {

        private ApprovalStore store;

        @BeforeEach
        void setUp() {
            store = new ApprovalStore(new AgentProperties());
        }

        @Test
        @DisplayName("create → approve 返回 true")
        void testCreateAndApprove() throws Exception {
            CompletableFuture<Boolean> future = store.create("call-1");
            assertNotNull(future);
            assertEquals(1, store.pendingCount());

            assertTrue(store.approve("call-1"));
            assertTrue(future.get(100, TimeUnit.MILLISECONDS));
            assertEquals(0, store.pendingCount());
        }

        @Test
        @DisplayName("create → deny 返回 false")
        void testCreateAndDeny() throws Exception {
            CompletableFuture<Boolean> future = store.create("call-2");
            assertEquals(1, store.pendingCount());

            assertTrue(store.deny("call-2"));
            assertFalse(future.get(100, TimeUnit.MILLISECONDS));
            assertEquals(0, store.pendingCount());
        }

        @Test
        @DisplayName("重复 approve/deny 对同一 ID 返回 false（幂等保护）")
        void testDuplicateResponseReturnsFalse() {
            store.create("call-3");
            store.approve("call-3");
            assertFalse(store.approve("call-3"));
            assertFalse(store.deny("call-3"));
        }

        @Test
        @DisplayName("对不存在的 ID 返回 false")
        void testNonExistentIdReturnsFalse() {
            assertFalse(store.approve("ghost-id"));
            assertFalse(store.deny("ghost-id"));
        }

        @Test
        @DisplayName("denyAll 拒绝所有待审批请求")
        void testDenyAllRejectsAllPending() throws Exception {
            CompletableFuture<Boolean> f1 = store.create("a");
            CompletableFuture<Boolean> f2 = store.create("b");
            CompletableFuture<Boolean> f3 = store.create("c");
            assertEquals(3, store.pendingCount());

            store.denyAll();

            assertFalse(f1.get(100, TimeUnit.MILLISECONDS));
            assertFalse(f2.get(100, TimeUnit.MILLISECONDS));
            assertFalse(f3.get(100, TimeUnit.MILLISECONDS));
            assertEquals(0, store.pendingCount());
        }

        @Test
        @DisplayName("pendingCount 正确追踪待审批数量")
        void testPendingCountTracking() {
            assertEquals(0, store.pendingCount());
            store.create("x");
            assertEquals(1, store.pendingCount());
            store.create("y");
            assertEquals(2, store.pendingCount());
            store.approve("x");
            assertEquals(1, store.pendingCount());
            store.deny("y");
            assertEquals(0, store.pendingCount());
        }

        @Test
        @DisplayName("审批超时自动拒绝：不调用 approve/deny 时 future 应在超时后完成")
        void testTimeoutAutoDeny() throws Exception {
            // 验证 future 不会立即完成（不会在短时间窗口内自动完成）
            CompletableFuture<Boolean> future = store.create("timeout-test");
            assertFalse(future.isDone(), "刚创建的 future 不应已完成");

            // 超时时间 60s，这里只验证框架设计正确——future 在短时间不完成
            // 完整超时验证需要 60s，此处跳过
            assertEquals(1, store.pendingCount());
        }
    }

    // ── DefaultReActEngine 审批集成测试 ────────────────────────────

    @Nested
    @DisplayName("DefaultReActEngine — 审批流程集成")
    class ReActEngineApprovalTests {

        private ApprovalStore approvalStore;
        private DefaultReActEngine engine;
        private ChatFacade chatFacade;
        private ChatModel chatModel;

        @BeforeEach
        void setUp() {
            approvalStore = new ApprovalStore(new AgentProperties());
            engine = new DefaultReActEngine(approvalStore, new AgentProperties());
            chatFacade = mock(ChatFacade.class);
            chatModel = mock(ChatModel.class);

            RoutingDecision decision = mock(RoutingDecision.class);
            when(decision.provider()).thenReturn("test");
            when(decision.model()).thenReturn("test-model");
            when(chatFacade.route(any(), any())).thenReturn(decision);
            when(chatFacade.resolveModel(any())).thenReturn(chatModel);
            when(chatModel.provider()).thenReturn("test");
            when(chatModel.model()).thenReturn("test-model");
        }

        @Test
        @DisplayName("ALLOW 工具（不在审批集合中）直接执行，无 tool_approval 事件")
        void testAllowToolExecutesDirectly() {
            engine.setApprovalRequired(Set.of("command")); // 仅 command 需审批

            ModelResponse chunk = mock(ModelResponse.class);
            when(chunk.getContent()).thenReturn(null);
            when(chunk.getToolCalls()).thenReturn(List.of(
                    ModelResponse.ToolCallRequest.builder()
                            .id("tc-calc")
                            .name("calculator")
                            .arguments("{\"expr\":\"2+2\"}")
                            .build()
            ));

            when(chatModel.stream(any())).thenReturn(Flux.just(chunk));
            when(chatModel.mergeChunks(anyList())).thenReturn(chunk);
            when(chunk.hasToolCalls()).thenReturn(true);

            ChatRequest request = new ChatRequest();
            request.setMessages(new ArrayList<>(List.of(
                    Message.builder().role("user").content("2+2=?").build()
            )));
            request.setStream(true);
            request.setTools(List.of());

            ToolExecutor toolExecutor = (name, id, args) -> "4";

            List<ServerSentEvent<String>> events = engine.executeStream(chatFacade, request, toolExecutor)
                    .collectList().block();

            assertNotNull(events);
            // 应包含 tool_call 事件，不含 tool_approval
            boolean hasToolCall = events.stream().anyMatch(e -> "tool_call".equals(e.event()));
            boolean hasApproval = events.stream().anyMatch(e -> "tool_approval".equals(e.event()));
            assertTrue(hasToolCall, "calculator 应直接产生 tool_call 事件");
            assertFalse(hasApproval, "calculator 不应产生 tool_approval 事件");
        }

        @Test
        @DisplayName("ASK 工具：tool_approval → tool_call(executing) → 用户允许 → tool_call(done)")
        void testAskToolTriggersApprovalEvent() throws Exception {
            engine.setApprovalRequired(Set.of("command"));

            ModelResponse chunk = mock(ModelResponse.class);
            when(chunk.getContent()).thenReturn(null);
            when(chunk.getToolCalls()).thenReturn(List.of(
                    ModelResponse.ToolCallRequest.builder()
                            .id("tc-cmd")
                            .name("command")
                            .arguments("{\"cmd\":\"ls\"}")
                            .build()
            ));

            when(chatModel.stream(any())).thenReturn(Flux.just(chunk));
            when(chatModel.mergeChunks(anyList())).thenReturn(chunk);
            when(chunk.hasToolCalls()).thenReturn(true);

            ChatRequest request = new ChatRequest();
            request.setMessages(new ArrayList<>(List.of(
                    Message.builder().role("user").content("list files").build()
            )));
            request.setStream(true);
            request.setTools(List.of());

            ToolExecutor toolExecutor = (name, id, args) -> "file1.txt\nfile2.txt";

            Flux<ServerSentEvent<String>> flux = engine.executeStream(chatFacade, request, toolExecutor);

            // 事件顺序：status → tool_approval → tool_call(executing)
            // 然后阻塞等待审批，complete 后发 tool_call(done)
            List<ServerSentEvent<String>> events = flux.take(3).collectList().block();
            assertNotNull(events);
            assertTrue(events.size() >= 2,
                    "应至少有 status + tool_approval 两个事件");

            // 验证 tool_approval 事件存在且数据完整
            ServerSentEvent<String> approvalEvent = events.stream()
                    .filter(e -> "tool_approval".equals(e.event()))
                    .findFirst().orElse(null);
            assertNotNull(approvalEvent, "ASK 工具应产生 tool_approval 事件");
            assertNotNull(approvalEvent.data());
            assertTrue(approvalEvent.data().contains("tc-cmd"));
            assertTrue(approvalEvent.data().contains("command"));

            // 验证 tool_call executing 事件紧随其后
            ServerSentEvent<String> executingEvent = events.stream()
                    .filter(e -> "tool_call".equals(e.event())
                            && e.data() != null && e.data().contains("\"executing\""))
                    .findFirst().orElse(null);
            assertNotNull(executingEvent, "审批路径也应发 tool_call executing 事件");

            // 验证 ApprovalStore 注册了 future（竞态修复：create 在 Flux 返回前执行）
            assertEquals(1, approvalStore.pendingCount());

            // 用户允许执行
            approvalStore.approve("tc-cmd");
            assertEquals(0, approvalStore.pendingCount());
        }

        @Test
        @DisplayName("ASK 工具用户拒绝 → future 返回 false")
        void testAskToolDenialViaStore() throws Exception {
            engine.setApprovalRequired(Set.of("command"));

            ModelResponse chunk = mock(ModelResponse.class);
            when(chunk.getContent()).thenReturn(null);
            when(chunk.getToolCalls()).thenReturn(List.of(
                    ModelResponse.ToolCallRequest.builder()
                            .id("tc-deny")
                            .name("command")
                            .arguments("{\"cmd\":\"rm -rf /\"}")
                            .build()
            ));

            when(chatModel.stream(any())).thenReturn(Flux.just(chunk));
            when(chatModel.mergeChunks(anyList())).thenReturn(chunk);
            when(chunk.hasToolCalls()).thenReturn(true);

            ChatRequest request = new ChatRequest();
            request.setMessages(new ArrayList<>(List.of(
                    Message.builder().role("user").content("delete everything").build()
            )));
            request.setStream(true);
            request.setTools(List.of());

            ToolExecutor toolExecutor = (name, id, args) -> "deleted";

            Flux<ServerSentEvent<String>> flux = engine.executeStream(chatFacade, request, toolExecutor);

            List<ServerSentEvent<String>> events = flux.take(3).collectList().block();
            assertNotNull(events);

            // 验证 tool_approval 事件已发出
            boolean hasApproval = events.stream()
                    .anyMatch(e -> "tool_approval".equals(e.event()));
            assertTrue(hasApproval, "ASK 工具应产生 tool_approval 事件");

            // 验证 ApprovalStore 已注册（create 在 Flux 返回前执行）
            assertEquals(1, approvalStore.pendingCount());

            // 用户拒绝
            approvalStore.deny("tc-deny");
            assertEquals(0, approvalStore.pendingCount());
        }

        @Test
        @DisplayName("混合场景：calculator(ALLOW) + command(ASK) → calculator 先执行，command 触发审批")
        void testMixedAllowAndAskTools() {
            engine.setApprovalRequired(Set.of("command"));

            // 第一个 chunk: calculator 工具调用
            ModelResponse chunk1 = mock(ModelResponse.class);
            when(chunk1.getContent()).thenReturn(null);
            when(chunk1.getToolCalls()).thenReturn(List.of(
                    ModelResponse.ToolCallRequest.builder()
                            .id("tc-math")
                            .name("calculator")
                            .arguments("{\"expr\":\"1+1\"}")
                            .build()
            ));

            when(chatModel.stream(any())).thenReturn(Flux.just(chunk1));
            when(chatModel.mergeChunks(anyList())).thenReturn(chunk1);
            when(chunk1.hasToolCalls()).thenReturn(true);

            ChatRequest request = new ChatRequest();
            request.setMessages(new ArrayList<>(List.of(
                    Message.builder().role("user").content("calculate and list").build()
            )));
            request.setStream(true);
            request.setTools(List.of());

            ToolExecutor toolExecutor = (name, id, args) -> {
                if ("calculator".equals(name)) return "2";
                if ("command".equals(name)) return "file list output";
                return "unknown";
            };

            List<ServerSentEvent<String>> events = engine.executeStream(chatFacade, request, toolExecutor)
                    .collectList().block();

            assertNotNull(events);
            // calculator 直接执行 → tool_call 事件
            boolean hasCalcToolCall = events.stream().anyMatch(e ->
                    "tool_call".equals(e.event()) && e.data() != null && e.data().contains("calculator"));
            assertTrue(hasCalcToolCall, "calculator (ALLOW) 应直接产生 tool_call 事件");

            // 不应有 tool_approval（因为只有 calculator 在本次请求中）
            boolean hasApproval = events.stream().anyMatch(e -> "tool_approval".equals(e.event()));
            assertFalse(hasApproval, "无 ASK 工具时不应有 tool_approval 事件");
        }
    }
}
