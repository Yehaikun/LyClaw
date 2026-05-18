package lyjew.com.lyclaw.web.controller;

import java.util.Map;
import java.util.UUID;

import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.orchestration.OrchestrationService;
import lyjew.com.lyclaw.orchestration.dto.ChatRequest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 聊天和会话管理 HTTP 控制器。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final OrchestrationService orchestrationService;

    public ChatController(OrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String requestTraceId,
            ServerHttpResponse response) {
        String traceId = (requestTraceId != null && !requestTraceId.isBlank())
                ? requestTraceId : UUID.randomUUID().toString().replace("-", "");
        response.getHeaders().add("X-Trace-Id", traceId);
        Session session = orchestrationService.resolveSession(request.getSessionId());
        return orchestrationService.chatStream(request, traceId, session);
    }

    @PostMapping("/chat")
    public Mono<ChatResult> chat(@RequestBody ChatRequest request) {
        return orchestrationService.chat(request);
    }

    @PostMapping("/sessions")
    public Session createSession(@RequestBody(required = false) ChatRequest request) {
        return orchestrationService.createSession(request);
    }

    @GetMapping("/sessions/{sessionId}")
    public Session getSession(@PathVariable String sessionId) {
        return orchestrationService.getSession(sessionId);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String sessionId) {
        return orchestrationService.deleteSession(sessionId);
    }
}
