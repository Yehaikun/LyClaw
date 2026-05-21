package lyjew.com.lyclaw.web.controller;

import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.web.agent.ChatAgent;
import lyjew.com.lyclaw.web.session.SessionManager;
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
    private final SessionManager sessionManager;

    public ChatController(ChatAgent chatAgent, SessionManager sessionManager) {
        this.chatAgent = chatAgent;
        this.sessionManager = sessionManager;
    }

    @Operation(summary = "流式聊天", description = "发送消息并以SSE流式返回AI响应，包含思考过程、工具调用等事件")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request,
                                                     @Parameter(description = "可选的Agent ID") @RequestParam(required = false) String agentId) {
        String resolvedAgentId = agentId != null ? agentId : "chat";
        Session session = resolveSession(request, resolvedAgentId);
        String userMessage = request.getLastUserMessage();
        return chatAgent.chatStream(userMessage);
    }

    @Operation(summary = "同步聊天", description = "发送消息并返回完整AI响应（非流式）")
    @PostMapping("/chat")
    public Mono<Map<String, Object>> chat(@RequestBody ChatRequest request,
                                          @Parameter(description = "可选的Agent ID") @RequestParam(required = false) String agentId) {
        String resolvedAgentId = agentId != null ? agentId : "chat";
        Session session = resolveSession(request, resolvedAgentId);
        String userMessage = request.getLastUserMessage();
        return Mono.fromCallable(() -> chatAgent.chat(userMessage))
                .subscribeOn(Schedulers.boundedElastic())
                .map(reply -> Map.of("content", reply, "sessionId", session.getSessionId()));
    }

    @Operation(summary = "创建会话", description = "创建Agent的新聊天会话并返回会话信息")
    @PostMapping("/agents/{agentId}/sessions")
    public Session createSession(@PathVariable String agentId,
                                  @RequestBody(required = false) ChatRequest request) {
        return sessionManager.createSession(agentId,
                request != null ? request.getModel() : null);
    }

    @Operation(summary = "获取会话", description = "根据Agent ID和会话ID获取会话信息")
    @GetMapping("/agents/{agentId}/sessions/{sessionId}")
    public Session getSession(@PathVariable String agentId,
                               @PathVariable String sessionId) {
        return sessionManager.getSession(sessionId);
    }

    @Operation(summary = "删除会话", description = "删除Agent的指定会话及其所有消息记录")
    @DeleteMapping("/agents/{agentId}/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String agentId,
                                              @PathVariable String sessionId) {
        sessionManager.deleteSession(sessionId);
        return Map.of("sessionId", sessionId, "deleted", true);
    }

    /**
     * 解析会话：如果请求包含sessionId则续接已有会话，
     * 否则创建新会话。会话不存在时fallback创建新会话。
     */
    private Session resolveSession(ChatRequest request, String agentId) {
        if (request.getSessionId() != null && !request.getSessionId().isEmpty()) {
            Session existing = sessionManager.getSession(request.getSessionId());
            if (existing != null) return existing;
        }
        return sessionManager.createSession(agentId, request.getModel());
    }
}
