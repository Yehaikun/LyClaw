package lyjew.com.lyclaw.orchestration;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.InterceptorChain;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.orchestration.dto.ChatRequest;
import lyjew.com.lyclaw.storage.StorageFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;

/**
 * 编排服务，封装会话管理和聊天编排的纯业务逻辑。
 *
 * <p>不依赖 HTTP 层，由 facade 模块的 Controller 调用。
 */
@Service
public class OrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationService.class);

    private final Orchestrator orchestrator;
    private final InterceptorChain interceptorChain;
    private final ChatFacade chatFacade;
    private final StorageFacade storageFacade;

    public OrchestrationService(Orchestrator orchestrator,
                                 InterceptorChain interceptorChain,
                                 ChatFacade chatFacade,
                                 StorageFacade storageFacade) {
        this.orchestrator = orchestrator;
        this.interceptorChain = interceptorChain;
        this.chatFacade = chatFacade;
        this.storageFacade = storageFacade;
    }

    /**
     * 流式聊天。
     *
     * @param dto      聊天请求 DTO
     * @param traceId  追踪 ID
     * @param session  当前会话
     * @return SSE 事件流
     */
    public Flux<ServerSentEvent<String>> chatStream(ChatRequest dto, String traceId, Session session) {
        lyjew.com.lyclaw.model.ChatRequest modelRequest = buildModelRequest(dto);
        ChatContext context = buildChatContext(modelRequest, session, traceId);
        log.info("收到流式请求: traceId={}, sessionId={}", traceId, dto.getSessionId());
        return orchestrator.execute(context);
    }

    /**
     * 同步聊天。
     *
     * @param dto 聊天请求 DTO
     * @return 合并后的聊天结果
     */
    public Mono<ChatResult> chat(ChatRequest dto) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        Session session = resolveSession(dto.getSessionId());
        lyjew.com.lyclaw.model.ChatRequest modelRequest = buildModelRequest(dto);
        ChatContext context = buildChatContext(modelRequest, session, traceId);
        return orchestrator.execute(context)
                .collectList()
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

    /**
     * 创建新会话。
     */
    public Session createSession(ChatRequest request) {
        String sessionId = UUID.randomUUID().toString();
        Session session = Session.builder()
                .sessionId(sessionId)
                .name("New Session")
                .build();
        storageFacade.save("sessions", sessionId, session);
        return session;
    }

    /**
     * 获取指定会话。
     */
    public Session getSession(String sessionId) {
        return storageFacade.load("sessions", sessionId, Session.class)
                .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
    }

    /**
     * 删除指定会话。
     */
    public Map<String, Object> deleteSession(String sessionId) {
        boolean existed = storageFacade.load("sessions", sessionId, Session.class).isPresent();
        storageFacade.delete("sessions", sessionId);
        Map<String, Object> result = new HashMap<>();
        result.put("deleted", existed);
        result.put("sessionId", sessionId);
        return result;
    }

    /**
     * 解析会话：如果已有则返回，否则创建新会话。
     */
    public Session resolveSession(String sessionId) {
        if (sessionId != null) {
            Optional<Session> existing = storageFacade.load("sessions", sessionId, Session.class);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        String newId = sessionId != null ? sessionId : UUID.randomUUID().toString();
        Session session = Session.builder()
                .sessionId(newId)
                .name("Auto Session")
                .build();
        storageFacade.save("sessions", newId, session);
        log.info("会话已加载: sessionId={}, 历史消息数={}", newId, session.getMessages().size());
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

    private ChatContext buildChatContext(lyjew.com.lyclaw.model.ChatRequest modelRequest,
                                          Session session, String traceId) {
        MemoryContent memory = new MemoryContent("", "", true, Collections.emptyList(), 0.0);
        return new ChatContext(
                modelRequest,
                session,
                memory,
                Collections.emptyList(),
                interceptorChain,
                chatFacade,
                traceId
        );
    }
}
