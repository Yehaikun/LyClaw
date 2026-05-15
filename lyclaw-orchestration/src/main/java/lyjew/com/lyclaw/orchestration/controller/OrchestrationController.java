package lyjew.com.lyclaw.orchestration.controller;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.InterceptorChain;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.orchestration.Orchestrator;
import lyjew.com.lyclaw.orchestration.dto.ChatRequest;
import lyjew.com.lyclaw.storage.StorageFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.Optional;

/**
 * 编排服务 REST 控制器。
 *
 * 提供聊天（流式/同步）、会话管理的 HTTP 端点。
 * 流式聊天使用 SSE (Server-Sent Events) 协议，通过 Flux 持续推送数据。
 * 会话存储使用 ConcurrentHashMap 实现简单的内存级会话管理。
 */
@RestController
@RequestMapping("/api")
public class OrchestrationController {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationController.class);
    private final Orchestrator orchestrator;
    private final InterceptorChain interceptorChain;
    private final ChatFacade chatFacade;
    private final StorageFacade storageFacade;

    public OrchestrationController(Orchestrator orchestrator,
                                   InterceptorChain interceptorChain,
                                   ChatFacade chatFacade,
                                   StorageFacade storageFacade) {
        this.orchestrator = orchestrator;
        this.interceptorChain = interceptorChain;
        this.chatFacade = chatFacade;
        this.storageFacade = storageFacade;
    }

    /**
     * 流式聊天端点（SSE）。
     *
     * 接收聊天请求，解析会话，构建上下文后委托给 Orchestrator 执行管线。
     * 响应以 text/event-stream 格式持续推送，直到管线完成或出错。
     *
     * @param request         聊天请求 DTO
     * @param requestTraceId  HTTP 头中的可选追踪 ID
     * @param response        服务端 HTTP 响应对象，用于设置响应头
     * @return SSE 事件流 Flux
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String requestTraceId,
            ServerHttpResponse response) {
        // 优先使用请求头传入的 traceId，否则生成新的
        String traceId = (requestTraceId != null && !requestTraceId.isBlank())
                ? requestTraceId : UUID.randomUUID().toString().replace("-", "");
        // 在响应头中回传 traceId，便于客户端追踪
        response.getHeaders().add("X-Trace-Id", traceId);
        // 解析或创建会话
        Session session = resolveSession(request.getSessionId());
        // 将 DTO 转换为内部 domain 模型
        lyjew.com.lyclaw.model.ChatRequest modelRequest = buildModelRequest(request);
        // 组装聊天上下文
        ChatContext context = buildChatContext(modelRequest, session, traceId);
        //记录日志
        log.info("收到流式请求: traceId={}, sessionId={}", traceId, request.getSessionId());
        // 委托编排器执行完整管线
        return orchestrator.execute(context);
    }

    /**
     * 同步聊天端点。
     *
     * 与流式端点逻辑类似，但将所有 SSE 事件收集后合并为单个 ChatResult 返回。
     * 适用于不需要实时流式输出的场景。
     *
     * @param request 聊天请求 DTO
     * @return 合并后的聊天结果 Mono
     */
    @PostMapping("/chat")
    public Mono<ChatResult> chat(@RequestBody ChatRequest request) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        Session session = resolveSession(request.getSessionId());
        lyjew.com.lyclaw.model.ChatRequest modelRequest = buildModelRequest(request);
        ChatContext context = buildChatContext(modelRequest, session, traceId);
        Flux<ServerSentEvent<String>> flux = orchestrator.execute(context);
        // 将所有 SSE 事件收集后，筛选 message 类型的事件并拼接内容
        return flux.collectList()
                .map(results -> {
                    String content = results != null
                            ? results.stream()
                                    .filter(e -> "message".equals(e.event()))   // 只取 message 事件
                                    .map(e -> e.data() != null ? e.data() : "")
                                    .reduce("", String::concat)                 // 拼接所有消息片段
                            : "";
                    return new ChatResult(content, "stop", null, null, 0L);
                })
                .subscribeOn(Schedulers.boundedElastic());  // 在弹性线程池订阅，避免阻塞事件循环
    }

    /**
     * 创建新会话。
     *
     * @param request 可选的请求体（用于携带会话名称等元信息）
     * @return 新创建的 Session
     */
    @PostMapping("/sessions")
    public Session createSession(@RequestBody(required = false) ChatRequest request) {
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
     *
     * @param sessionId 会话 ID
     * @return 对应的 Session
     * @throws NoSuchElementException 会话不存在时抛出
     */
    @GetMapping("/sessions/{sessionId}")
    public Session getSession(@PathVariable String sessionId) {
        return storageFacade.load("sessions", sessionId, Session.class)
                .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
    }

    /**
     * 删除指定会话。
     *
     * @param sessionId 会话 ID
     * @return 包含删除状态和会话 ID 的 Map
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String sessionId) {
        boolean existed = storageFacade.load("sessions", sessionId, Session.class).isPresent();
        storageFacade.delete("sessions", sessionId);
        Map<String, Object> result = new HashMap<>();
        result.put("deleted", existed);
        result.put("sessionId", sessionId);
        return result;
    }

    /**
     * 解析会话：如果已有则返回，否则创建新会话并存入 store。
     *
     * @param sessionId 会话 ID，可为 null
     * @return 已存在或新创建的 Session
     */
    private Session resolveSession(String sessionId) {
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
        log.info("会话已加载: sessionId={}, 历史消息数={}", sessionId, session.getMessages().size());
        return session;
    }

    /**
     * 将 DTO 层的 ChatRequest 转换为 domain 模型层的 ChatRequest。
     * 松散的消息 Map 结构被转换为强类型的 Message 对象列表。
     *
     * @param dto 客户端传入的 DTO
     * @return domain 层的 ChatRequest
     */
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

    /**
     * 组装 ChatContext。
     * 聚合请求、会话、记忆、拦截器链、模型提供者和追踪 ID。
     *
     * @param modelRequest domain 层请求
     * @param session      当前会话
     * @param traceId      追踪 ID
     * @return 组装好的 ChatContext
     */
    private ChatContext buildChatContext(lyjew.com.lyclaw.model.ChatRequest modelRequest, Session session, String traceId) {
        // 创建空的记忆内容作为初始状态
        MemoryContent memory = new MemoryContent("", "", true, Collections.emptyList(), 0.0);
        return new ChatContext(
                modelRequest,
                session,
                memory,
                Collections.emptyList(),  // 工具定义由后续管线阶段填充
                interceptorChain,
                chatFacade,
                traceId
        );
    }
}
