package lyjew.com.lyclaw.orchestration.controller;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.InterceptorChain;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.orchestration.Orchestrator;
import lyjew.com.lyclaw.orchestration.dto.ChatRequest;
import lyjew.com.lyclaw.provider.ModelProvider;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class OrchestrationController {

    private final Orchestrator orchestrator;
    private final InterceptorChain interceptorChain;
    private final ModelProvider modelProvider;
    private final Map<String, Session> sessionStore = new ConcurrentHashMap<>();

    public OrchestrationController(Orchestrator orchestrator,
                                   InterceptorChain interceptorChain,
                                   ModelProvider modelProvider) {
        this.orchestrator = orchestrator;
        this.interceptorChain = interceptorChain;
        this.modelProvider = modelProvider;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String requestTraceId,
            ServerHttpResponse response) {
        String traceId = (requestTraceId != null && !requestTraceId.isBlank())
                ? requestTraceId : UUID.randomUUID().toString().replace("-", "");
        response.getHeaders().add("X-Trace-Id", traceId);
        Session session = resolveSession(request.getSessionId());
        lyjew.com.lyclaw.model.ChatRequest modelRequest = buildModelRequest(request);
        ChatContext context = buildChatContext(modelRequest, session, traceId);
        return orchestrator.execute(context);
    }

    @PostMapping("/chat")
    public Mono<ChatResult> chat(@RequestBody ChatRequest request) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        Session session = resolveSession(request.getSessionId());
        lyjew.com.lyclaw.model.ChatRequest modelRequest = buildModelRequest(request);
        ChatContext context = buildChatContext(modelRequest, session, traceId);
        Flux<ServerSentEvent<String>> flux = orchestrator.execute(context);
        return flux.collectList()
                .map(results -> {
                    String content = results != null
                            ? results.stream()
                                    .filter(e -> "message".equals(e.event()))
                                    .map(e -> e.data() != null ? e.data() : "")
                                    .reduce("", String::concat)
                            : "";
                    return new ChatResult(content, "stop", null, null, 0L);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/sessions")
    public Session createSession(@RequestBody(required = false) ChatRequest request) {
        String sessionId = UUID.randomUUID().toString();
        Session session = Session.builder()
                .sessionId(sessionId)
                .name("New Session")
                .build();
        sessionStore.put(sessionId, session);
        return session;
    }

    @GetMapping("/sessions/{sessionId}")
    public Session getSession(@PathVariable String sessionId) {
        Session session = sessionStore.get(sessionId);
        if (session == null) {
            throw new NoSuchElementException("Session not found: " + sessionId);
        }
        return session;
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String sessionId) {
        Session removed = sessionStore.remove(sessionId);
        Map<String, Object> result = new HashMap<>();
        result.put("deleted", removed != null);
        result.put("sessionId", sessionId);
        return result;
    }

    private Session resolveSession(String sessionId) {
        if (sessionId != null && sessionStore.containsKey(sessionId)) {
            return sessionStore.get(sessionId);
        }
        String newId = sessionId != null ? sessionId : UUID.randomUUID().toString();
        Session session = Session.builder()
                .sessionId(newId)
                .name("Auto Session")
                .build();
        sessionStore.put(newId, session);
        return session;
    }

    private lyjew.com.lyclaw.model.ChatRequest buildModelRequest(ChatRequest dto) {
        List<Message> messages = new ArrayList<>();
        if (dto.getMessages() != null) {
            for (Map<String, String> entry : dto.getMessages()) {
                Message msg = Message.builder()
                        .role(entry.getOrDefault("role", "user"))
                        .content(entry.getOrDefault("content", ""))
                        .build();
                messages.add(msg);
            }
        }
        return lyjew.com.lyclaw.model.ChatRequest.builder()
                .sessionId(dto.getSessionId() != null ? dto.getSessionId() : "")
                .messages(messages)
                .stream(dto.isStream())
                .build();
    }

    private ChatContext buildChatContext(lyjew.com.lyclaw.model.ChatRequest modelRequest, Session session, String traceId) {
        MemoryContent memory = new MemoryContent("", "", true, Collections.emptyList(), 0.0);
        return new ChatContext(
                modelRequest,
                session,
                memory,
                Collections.emptyList(),
                interceptorChain,
                modelProvider,
                traceId
        );
    }
}
