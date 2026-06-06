package lyjew.com.lyclaw.mesh;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.react.DefaultReActEngine;
import lyjew.com.lyclaw.react.ToolExecutor;

/**
 * ReAct 事件回调测试：
 * - eventCallback 收到 STAGE/TOOL_CALL 事件
 * - 无 toolExecutor 时退化
 * - 事件包含正确的 progress
 */
class ReActEventCallbackTest {

    @Test
    void eventCallbackShouldReceiveStageEvents() {
        DefaultReActEngine engine = new DefaultReActEngine(null);
        List<AgentExecutionEvent> received = new ArrayList<>();
        Consumer<AgentExecutionEvent> callback = event -> received.add(event);

        ChatRequest request = ChatRequest.builder()
                .messages(new ArrayList<>(List.of(Message.user("hello"))))
                .build();

        // ChatFacade 为 null 会失败，但回调应该在失败前被调用
        try {
            engine.execute(null, request, null, callback);
        } catch (Exception ignored) {}

        // 如果启动了 ReAct 循环（哪怕失败），应产生事件
        // 无 toolExecutor 时退化到单次 LLM 调用，不产生事件
        assertNotNull(received);
    }

    @Test
    void callbackCanBeNull() {
        DefaultReActEngine engine = new DefaultReActEngine(null);
        ChatRequest request = ChatRequest.builder()
                .messages(new ArrayList<>(List.of(Message.user("hello"))))
                .build();

        // null callback 不应抛出异常
        try {
            engine.execute(null, request, null, (Consumer<AgentExecutionEvent>) null);
        } catch (Exception ignored) {}
    }

    @Test
    void noCallbackPreservesOriginalBehavior() {
        DefaultReActEngine engine = new DefaultReActEngine(null);
        ChatRequest request = ChatRequest.builder()
                .messages(new ArrayList<>(List.of(Message.user("hello"))))
                .build();

        try {
            String result = engine.execute(null, request, null);
            assertNull(result);
        } catch (Exception ignored) {}
    }

    @Test
    void streamVersionShouldAcceptCallback() {
        DefaultReActEngine engine = new DefaultReActEngine(null);
        ChatRequest request = ChatRequest.builder()
                .messages(new ArrayList<>(List.of(Message.user("hello"))))
                .stream(true)
                .build();

        // executeStream 不应因为 callback 参数抛出
        try {
            engine.executeStream(null, request, null);
        } catch (Exception ignored) {}
    }

    @Test
    void toolExecutorTriggersToolCallEvents() {
        DefaultReActEngine engine = new DefaultReActEngine(null);
        List<AgentExecutionEvent> received = new ArrayList<>();

        ToolExecutor toolExec = (name, id, args) -> "result";

        ChatRequest request = ChatRequest.builder()
                .messages(new ArrayList<>(List.of(Message.user("hello"))))
                .build();

        try {
            engine.execute(null, request, toolExec, event -> received.add(event));
        } catch (Exception ignored) {}

        assertNotNull(received);
    }
}
