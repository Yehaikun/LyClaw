package lyjew.com.lyclaw.web.controller;

import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.web.agent.ChatAgent;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Tag(name = "Chat", description = "聊天和会话管理接口")
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatAgent chatAgent;

    public ChatController(ChatAgent chatAgent) {
        this.chatAgent = chatAgent;
    }

    @Operation(summary = "流式聊天", description = "发送消息并以SSE流式返回AI响应，包含思考过程、工具调用等事件")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request,
                                                     @Parameter(description = "可选的Agent ID") @RequestParam(required = false) String agentId) {
        String userMessage = request.getLastUserMessage();
        return chatAgent.chatStream(userMessage);
    }

    @Operation(summary = "同步聊天", description = "发送消息并返回完整AI响应（非流式）")
    @PostMapping("/chat")
    public Mono<Map<String, Object>> chat(@RequestBody ChatRequest request,
                                          @Parameter(description = "可选的Agent ID") @RequestParam(required = false) String agentId) {
        String userMessage = request.getLastUserMessage();
        String sessionId = request.getSessionId() != null ? request.getSessionId() : "";
        return Mono.fromCallable(() -> chatAgent.chat(userMessage))
                .subscribeOn(Schedulers.boundedElastic())
                .map(reply -> Map.of("content", reply, "sessionId", sessionId));
    }

    @Operation(summary = "创建会话", description = "创建一个新的聊天会话并返回会话信息")
    @PostMapping("/sessions")
    public Session createSession(@RequestBody(required = false) ChatRequest request) {
        Session session = new Session();
        session.setSessionId(UUID.randomUUID().toString().substring(0, 8));
        return session;
    }

    @Operation(summary = "获取会话", description = "根据会话ID获取会话信息")
    @GetMapping("/sessions/{sessionId}")
    public Session getSession(@Parameter(description = "会话ID") @PathVariable String sessionId) {
        Session session = new Session();
        session.setSessionId(sessionId);
        return session;
    }

    @Operation(summary = "删除会话", description = "根据会话ID删除会话")
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@Parameter(description = "会话ID") @PathVariable String sessionId) {
        return Map.of("sessionId", sessionId, "deleted", true);
    }
}
