package lyjew.com.lyclaw.web.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.web.agent.ChatAgent;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 聊天和会话管理 HTTP 控制器。
 * 直接注入 @Agent 代理 Bean，Stage 管线内嵌在代理调用中。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatAgent chatAgent;

    public ChatController(ChatAgent chatAgent) {
        this.chatAgent = chatAgent;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
        String userMessage = request.getLastUserMessage();
        return chatAgent.chatStream(userMessage);
    }

    @PostMapping("/chat")
    public Mono<Map<String, Object>> chat(@RequestBody ChatRequest request) {
        String userMessage = request.getLastUserMessage();
        String sessionId = request.getSessionId() != null ? request.getSessionId() : "";
        return Mono.fromCallable(() -> chatAgent.chat(userMessage))
                .subscribeOn(Schedulers.boundedElastic())
                .map(reply -> Map.of("content", reply, "sessionId", sessionId));
    }

    @PostMapping("/sessions")
    public Session createSession(@RequestBody(required = false) ChatRequest request) {
        Session session = new Session();
        session.setSessionId(UUID.randomUUID().toString().substring(0, 8));
        return session;
    }

    @GetMapping("/sessions/{sessionId}")
    public Session getSession(@PathVariable String sessionId) {
        Session session = new Session();
        session.setSessionId(sessionId);
        return session;
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String sessionId) {
        return Map.of("sessionId", sessionId, "deleted", true);
    }
}
