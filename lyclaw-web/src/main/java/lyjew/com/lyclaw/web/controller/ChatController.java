package lyjew.com.lyclaw.web.controller;

import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.web.agent.ChatAgent;
import lyjew.com.lyclaw.web.session.SessionManager;
import lyjew.com.lyclaw.react.SessionRequestContext;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Tag(name = "Chat", description = "聊天接口")
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatAgent chatAgent;
    private final SessionManager sessionManager;

    public ChatController(ChatAgent chatAgent, SessionManager sessionManager) {
        this.chatAgent = chatAgent;
        this.sessionManager = sessionManager;
    }

    @Operation(summary = "流式聊天", description = "发送消息并以SSE流式返回AI响应")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request,
                                                     @Parameter(description = "可选的Agent ID") @RequestParam(required = false) String agentId) {
        String resolvedAgentId = agentId != null ? agentId : "chat";
        boolean isNewSession = request.getSessionId() == null || request.getSessionId().isEmpty();
        Session session = resolveSession(request, resolvedAgentId);
        String userMessage = request.getLastUserMessage();

        String sessionJson = String.format(
                "{\"sessionId\":\"%s\",\"agentId\":\"%s\",\"isNew\":%s}",
                session.getSessionId(), resolvedAgentId, isNewSession);
        ServerSentEvent<String> sessionEvent = ServerSentEvent.<String>builder()
                .event("session_created")
                .data(sessionJson)
                .build();

        SessionRequestContext.set(session.getSessionId(), resolvedAgentId);
        try {
            return Flux.just(sessionEvent)
                    .concatWith(chatAgent.chatStream(userMessage))
                    .doFinally(signalType -> {
                        SessionRequestContext.clear();
                        lyjew.com.lyclaw.react.DefaultReActEngine.clearSessionEmitters(session.getSessionId());
                    });
        } catch (Exception e) {
            SessionRequestContext.clear();
            lyjew.com.lyclaw.react.DefaultReActEngine.clearSessionEmitters(session.getSessionId());
            throw e;
        }
    }

    @Operation(summary = "同步聊天", description = "发送消息并返回完整AI响应（非流式）")
    @PostMapping("/chat")
    public Mono<Map<String, Object>> chat(@RequestBody ChatRequest request,
                                          @Parameter(description = "可选的Agent ID") @RequestParam(required = false) String agentId) {
        String resolvedAgentId = agentId != null ? agentId : "chat";
        Session session = resolveSession(request, resolvedAgentId);
        String userMessage = request.getLastUserMessage();
        String sessionId = session.getSessionId();
        return Mono.fromCallable(() -> {
                    SessionRequestContext.set(sessionId, resolvedAgentId);
                    try {
                        return chatAgent.chat(userMessage);
                    } finally {
                        SessionRequestContext.clear();
                        lyjew.com.lyclaw.react.DefaultReActEngine.clearSessionEmitters(sessionId);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(reply -> Map.of("content", reply, "sessionId", sessionId));
    }

    @Operation(summary = "创建会话", description = "创建Agent的新聊天会话")
    @PostMapping("/agents/{agentId}/sessions")
    public Session createSession(@PathVariable String agentId,
                                  @RequestBody(required = false) ChatRequest request) {
        return sessionManager.createSession(agentId,
                request != null ? request.getModel() : null);
    }

    @Operation(summary = "获取会话", description = "获取会话信息")
    @GetMapping("/agents/{agentId}/sessions/{sessionId}")
    public Session getSession(@PathVariable String agentId,
                               @PathVariable String sessionId) {
        Session session = sessionManager.getSession(sessionId);
        if (session == null) throw new RuntimeException("会话不存在: " + sessionId);
        return session;
    }

    private Session resolveSession(ChatRequest request, String agentId) {
        if (request.getSessionId() != null && !request.getSessionId().isEmpty()) {
            Session existing = sessionManager.getSession(request.getSessionId());
            if (existing != null) return existing;
        }
        return sessionManager.createSession(agentId, request.getModel());
    }
}
