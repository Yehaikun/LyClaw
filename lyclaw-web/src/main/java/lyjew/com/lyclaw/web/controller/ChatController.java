package lyjew.com.lyclaw.web.controller;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.web.agent.ChatAgent;
import lyjew.com.lyclaw.react.SessionRequestContext;
import lyjew.com.lyclaw.session.SessionService;
import lyjew.com.lyclaw.session.SessionUpdate;
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
    private final SessionService sessionService;

    public ChatController(ChatAgent chatAgent, SessionService sessionService) {
        this.chatAgent = chatAgent;
        this.sessionService = sessionService;
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
        return sessionService.create(agentId,
                request != null ? request.getModel() : null);
    }

    @Operation(summary = "列出会话", description = "获取指定 Agent 的会话列表")
    @GetMapping("/agents/{agentId}/sessions")
    public List<Map<String, Object>> listSessions(@PathVariable String agentId) {
        return sessionService.listSessions(agentId);
    }

    @Operation(summary = "获取会话", description = "获取会话信息")
    @GetMapping("/agents/{agentId}/sessions/{sessionId}")
    public Session getSession(@PathVariable String agentId,
                               @PathVariable String sessionId) {
        return sessionService.get(sessionId)
                .orElseThrow(() -> new RuntimeException("会话不存在: " + sessionId));
    }

    @Operation(summary = "获取会话消息", description = "分页获取指定会话的消息历史")
    @GetMapping("/agents/{agentId}/sessions/{sessionId}/messages")
    public List<Message> getMessages(@PathVariable String agentId,
                                     @PathVariable String sessionId,
                                     @RequestParam(defaultValue = "0") int offset,
                                     @RequestParam(defaultValue = "200") int limit) {
        return sessionService.loadMessages(sessionId, offset, limit);
    }

    @Operation(summary = "重命名会话", description = "修改会话名称")
    @PatchMapping("/agents/{agentId}/sessions/{sessionId}")
    public Map<String, Object> renameSession(@PathVariable String agentId,
                                             @PathVariable String sessionId,
                                             @RequestBody Map<String, Object> body) {
        String name = body.get("name") != null ? body.get("name").toString() : "";
        sessionService.update(sessionId, new SessionUpdate().name(name));
        return Map.of("sessionId", sessionId, "name", name);
    }

    @Operation(summary = "删除会话", description = "删除指定会话及其消息")
    @DeleteMapping("/agents/{agentId}/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String agentId,
                                             @PathVariable String sessionId) {
        sessionService.delete(sessionId);
        return Map.of("deleted", true, "sessionId", sessionId);
    }

    private Session resolveSession(ChatRequest request, String agentId) {
        if (request.getSessionId() != null && !request.getSessionId().isEmpty()) {
            return sessionService.getOrCreate(request.getSessionId(), agentId, request.getModel());
        }
        return sessionService.create(agentId, request.getModel());
    }
}
