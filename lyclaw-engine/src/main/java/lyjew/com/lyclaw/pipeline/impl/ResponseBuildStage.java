package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.InterceptorChain;
import lyjew.com.lyclaw.memory.MemoryManager;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.persistence.PersistenceDecision;
import lyjew.com.lyclaw.persistence.executor.PersistenceExecutor;
import lyjew.com.lyclaw.persistence.memory.MemoryPersistence;
import lyjew.com.lyclaw.persistence.memory.MemoryWriteState;
import lyjew.com.lyclaw.persistence.session.SessionPersistence;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.Collections;
import org.springframework.stereotype.Component;

/**
 * Pipeline 第五阶段（最终阶段）—— 响应构建阶段。
 *
 * <p>负责：
 * <ol>
 *   <li>从 ChatContext 提取 AI 回复内容（最后一条 assistant 消息的 content）</li>
 *   <li>构建 ChatResult</li>
 *   <li>执行所有拦截器的 postHandle()</li>
 *   <li>通过持久化决策层执行持久化：记忆 + 会话</li>
 * </ol>
 * </p>
 *
 * <p><b>持久化重构</b>：不再直接调用 sessionStorage.save() 或 memoryManager.append()+persist()，
 * 改为通过持久化决策层（{@link PersistenceExecutor} + {@link SessionPersistence} + {@link MemoryPersistence}）
 * 决定何时落盘。决策与执行完全解耦。</p>
 *
 * <p><b>流式模式</b>：检测到 {@code __stream_flux__} 存在时，不立即同步构建
 * （此时 Flux 尚未完成，消息列表中的 content 为空），而是将构建逻辑注册到
 * Flux 的 doOnComplete 中异步执行。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ChatResult
 * @see InterceptorChain
 * @see PersistenceExecutor
 */
@Slf4j
@Component
public class ResponseBuildStage implements PipelineStage {

    private final InterceptorChain interceptorChain;
    private final MemoryManager memoryManager;
    /** 持久化执行器 —— 将决策映射到存储操作 */
    private final PersistenceExecutor persistenceExecutor;
    /** 会话持久化策略 —— 决定何时写入会话文件 */
    private final SessionPersistence sessionPersistence;
    /** 记忆持久化策略 —— 决定何时刷盘记忆文件 */
    private final MemoryPersistence memoryPersistence;

    /** 记忆累积变更状态，由 memoryPersistence 判断是否刷盘 (thread-safe) */
    private final java.util.concurrent.atomic.AtomicReference<MemoryWriteState> memoryWriteState =
            new java.util.concurrent.atomic.AtomicReference<>(MemoryWriteState.initial());

    /** 上次会话写入时间戳 (thread-safe) */
    private final java.util.concurrent.atomic.AtomicLong lastSessionWriteTimestamp =
            new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

    public ResponseBuildStage(InterceptorChain interceptorChain,
                              MemoryManager memoryManager,
                              PersistenceExecutor persistenceExecutor,
                              SessionPersistence sessionPersistence,
                              MemoryPersistence memoryPersistence) {
        this.interceptorChain = interceptorChain;
        this.memoryManager = memoryManager;
        this.persistenceExecutor = persistenceExecutor;
        this.sessionPersistence = sessionPersistence;
        this.memoryPersistence = memoryPersistence;
        log.info("  [ResponseBuildStage] 构造注入: memoryManager={}, sessionPersistence={}, memoryPersistence={}",
                memoryManager.getClass().getSimpleName(),
                sessionPersistence.getClass().getSimpleName(),
                memoryPersistence.getClass().getSimpleName());
    }

    @Override
    public void process(ChatContext context, Chain chain) {
        log.info("  [ResponseBuildStage] process 入口, stream={}", context.getRequest().isStream());

        // 流式模式：如果 Flux 已被 ToolCallLoopStage 消费完毕（blockLast），
        // 走同步持久化路径（doOnComplete 已触发，注册新的 doOnComplete 无效）
        if (Boolean.TRUE.equals(context.getAttribute("__stream_consumed__"))) {
            log.info("  [ResponseBuildStage] 流式 Flux 已消费完毕，走同步持久化");
            handleSyncPersistDirect(context);
            chain.next(context);
            return;
        }

        Object fluxObj = context.getAttribute("__stream_flux__");
        if (fluxObj instanceof Flux) {
            handleStream(context, (Flux<String>) fluxObj);
        } else {
            handleSync(context);
        }
        chain.next(context);
    }

    /**
     * 流式模式下 Flux 已被 blockLast 消费完毕，直接同步执行持久化。
     */
    private void handleSyncPersistDirect(ChatContext context) {
        String fullContent = (String) context.getAttribute("__stream_full_content__");
        if (fullContent == null || fullContent.isEmpty()) {
            log.warn("  [ResponseBuildStage] __stream_full_content__ 为空，跳过构建");
            return;
        }

        // 如果内容包含 SSE JSON 格式，强制提取纯文本
        if (fullContent.contains("data:{") || fullContent.contains("\"choices\"")) {
            String extracted = extractPlainText(fullContent);
            if (!extracted.isEmpty()) {
                log.info("  [ResponseBuildStage] 从 SSE JSON 提取纯文本: {}字 → {}字", fullContent.length(), extracted.length());
                fullContent = extracted;
            }
        }

        String tokenUsage = (String) context.getAttribute("__stream_token_usage__");
        if (tokenUsage == null) tokenUsage = "prompt=0 completion=0 total=0";

        context.getTracing().markEnd();
        long durationMs = context.getTracing().getTotalDuration();

        log.info("  [ResponseBuildStage] 构建 ChatResult: contentLen={}, tokenUsage={}, durationMs={}ms",
                fullContent.length(), tokenUsage, durationMs);

        ChatResult result = new ChatResult(fullContent, "stop", tokenUsage, Collections.emptyList(), durationMs);
        context.setResult(result);

        interceptorChain.postHandle(context, result);

        doPersist(context, fullContent);
    }

    /**
     * 流式模式：把构建 ChatResult + 持久化注册到 Flux.doOnComplete。
     * Flux 在 DefaultEngine.handleStreamResult 中被 Controller 消费，
     * 消费完成后触发 doOnComplete。
     */
    @SuppressWarnings("unchecked")
    private void handleStream(ChatContext context, Flux<String> streamFlux) {
        log.info("  [ResponseBuildStage] 流式模式：注册 doOnComplete 回调");

        Flux<String> decorated = streamFlux
                .doOnComplete(() -> {
                    log.info("  [ResponseBuildStage] doOnComplete: 流式输出完成，构建 ChatResult...");
                    doBuildAndPersist(context);
                })
                .doOnError(error -> {
                    log.error("  [ResponseBuildStage] 流式对话失败", error);
                    if (memoryManager != null) {
                        memoryManager.append("[流式对话失败] " + error.getMessage());
                    }
                });
        context.setAttribute("__stream_flux__", decorated);
    }

    /**
     * 同步模式：直接从消息列表提取 content，构建 ChatResult，持久化。
     */
    private void handleSync(ChatContext context) {
        String responseText = extractLastAssistantMessage(context);
        context.getTracing().markEnd();

        String tokenUsage = (String) context.getAttribute("__stream_token_usage__");
        if (tokenUsage == null) tokenUsage = "prompt=0 completion=0 total=0";

        long durationMs = context.getTracing().getTotalDuration();
        log.info("  [ResponseBuildStage] 构建 ChatResult: contentLen={}, tokenUsage={}, durationMs={}ms",
                responseText.length(), tokenUsage, durationMs);

        ChatResult result = new ChatResult(responseText, "stop", tokenUsage, Collections.emptyList(), durationMs);
        context.setResult(result);

        // postHandle 拦截器
        interceptorChain.postHandle(context, result);

        // 持久化（同步模式）
        persistSync(context, responseText);
    }

    /**
     * 在 doOnComplete 中执行构建 + 持久化（流式模式）。
     */
    private void doBuildAndPersist(ChatContext context) {
        // 1. 从属性获取纯文本 content
        String fullContent = (String) context.getAttribute("__stream_full_content__");
        if (fullContent == null || fullContent.isEmpty()) {
            log.warn("  [ResponseBuildStage] __stream_full_content__ 为空，跳过构建");
            return;
        }

        // 1.1 如果内容包含 SSE JSON 格式，强制提取纯文本
        if (fullContent.contains("data:{") || fullContent.contains("\"choices\"")) {
            String extracted = extractPlainText(fullContent);
            if (!extracted.isEmpty()) {
                log.info("  [ResponseBuildStage] 从 SSE JSON 提取纯文本: {}字 → {}字", fullContent.length(), extracted.length());
                fullContent = extracted;
            }
        }

        String tokenUsage = (String) context.getAttribute("__stream_token_usage__");
        if (tokenUsage == null) tokenUsage = "prompt=0 completion=0 total=0";

        context.getTracing().markEnd();
        long durationMs = context.getTracing().getTotalDuration();

        log.info("  [ResponseBuildStage] 构建 ChatResult: contentLen={}, tokenUsage={}, durationMs={}ms",
                fullContent.length(), tokenUsage, durationMs);

        // 2. 构建 ChatResult
        ChatResult result = new ChatResult(fullContent, "stop", tokenUsage, Collections.emptyList(), durationMs);
        context.setResult(result);

        // 3. postHandle 拦截器
        log.info("  [ResponseBuildStage] 执行 {} 个拦截器的 postHandle...",
                interceptorChain.getInterceptors().size());
        interceptorChain.postHandle(context, result);

        // 4. 持久化：通过决策层统一执行
        doPersist(context, fullContent);

        log.info("  [ResponseBuildStage] 流式请求处理完毕");
    }

    /**
     * 核心持久化逻辑 —— 通过持久化决策层执行。
     *
     * <p>流程：
     * <ol>
     *   <li>追加消息到会话对象</li>
     *   <li>会话持久化：决策 → 执行</li>
     *   <li>记忆追加（只在内存）</li>
     *   <li>记忆持久化：决策 → 执行</li>
     * </ol>
     * </p>
     */
    private void doPersist(ChatContext context, String content) {
        ModelAdapter adapter = context.getModelProvider().getConfiguredAdapter();

        // 1. 追加消息到会话
        Message assistantMsg = Message.builder()
                .role("assistant")
                .content(content)
                .model(adapter.getModel())
                .createdAt(LocalDateTime.now())
                .build();
        Session session = context.getSession();
        session.getMessages().add(assistantMsg);

        // 2. 会话持久化决策
        int turnCount = getTurnCount(context);
        long millisSinceLastWrite = System.currentTimeMillis() - lastSessionWriteTimestamp.get();
        PersistenceDecision sessionDecision = sessionPersistence.evaluate(session, turnCount, millisSinceLastWrite);
        log.debug("  [ResponseBuildStage] 会话持久化决策: {}", sessionDecision);
        persistenceExecutor.executeSessionWrite(session, sessionDecision);
        if (sessionDecision.shouldWrite()) {
            lastSessionWriteTimestamp.set(System.currentTimeMillis());
        }

        // 3. 记忆追加（只在内存，不刷盘）
        log.info("  [ResponseBuildStage] 记忆追加 ({} 字)...", content.length());
        memoryManager.append(content);

        // 4. 记忆持久化决策
        MemoryWriteState currentState = memoryWriteState.updateAndGet(s -> s.accumulate(content));
        PersistenceDecision memoryDecision = memoryPersistence.evaluate(currentState);
        log.debug("  [ResponseBuildStage] 记忆持久化决策: {}", memoryDecision);
        persistenceExecutor.executeMemoryFlush(memoryDecision);
        if (memoryDecision.shouldWrite()) {
            memoryWriteState.set(MemoryWriteState.initial());
        }
    }

    /**
     * 获取当前轮次号（从 context 属性读取）。
     */
    private int getTurnCount(ChatContext context) {
        Integer round = (Integer) context.getAttribute("__round__");
        return round != null ? round : 0;
    }

    /**
     * 从字符串中提取纯文本，过滤掉 SSE JSON 格式。
     */
    private String extractPlainText(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder text = new StringBuilder();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"content\":\"((?:[^\"\\\\]|\\\\.)*)\"");
        java.util.regex.Matcher m = p.matcher(raw);
        while (m.find()) {
            String val = m.group(1);
            if (val != null && !val.isEmpty()) {
                text.append(val);
            }
        }
        return text.toString();
    }

    /**
     * 同步模式持久化 —— 通过持久化决策层执行。
     */
    private void persistSync(ChatContext context, String content) {
        if (content.isEmpty()) {
            log.warn("  [ResponseBuildStage] content 为空，跳过持久化");
            return;
        }
        doPersist(context, content);
    }

    private String extractLastAssistantMessage(ChatContext context) {
        for (int i = context.getRequest().getMessages().size() - 1; i >= 0; i--) {
            Message msg = context.getRequest().getMessages().get(i);
            if ("assistant".equals(msg.getRole())) {
                return msg.getContent();
            }
        }
        return "";
    }

    @Override
    public int getOrder() {
        return 4;
    }

    @Override
    public String getStageName() {
        return "ResponseBuild";
    }
}
