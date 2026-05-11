package lyjew.com.lyclaw.orchestration.pipeline;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.feign.MemoryFeignClient;
import lyjew.com.lyclaw.interceptor.InterceptorChain;
import lyjew.com.lyclaw.memory.MemoryManager;
import lyjew.com.lyclaw.memory.PerceptionData;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 响应构建阶段 —— 流水线的最后阶段（order=4）。
 *
 * 负责将 LLM 生成的响应内容（同步或流式）构建为 ChatResult 并持久化。
 * 主要工作包括：
 * 1. 从上下文中获取 LLM 响应文本（支持流式装饰器延迟构建和同步直接构建两路分支）。
 * 2. 从原始 SSE/JSON 响应中提取纯文本（extractPlainText），避免将 API 格式数据返回给客户端。
 * 3. 构建 ChatResult 对象，记录 token 用量和耗时，并通过拦截器链的 postHandle 后处理。
 * 4. 将 assistant 消息追加到会话记录，同时通过 MemoryManager 和 MemoryFeignClient 持久化记忆。
 *
 * 该阶段是流水线的收尾环节，执行后将完成一次完整的请求-响应周期。
 */
@Slf4j
@Component
public class ResponseBuildStage implements PipelineStage {

    /** 用于从 SSE/JSON 数据中提取 content 字段的正则表达式 */
    private static final Pattern CONTENT_PATTERN = Pattern.compile("\"content\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** 拦截器链，执行 postHandle 后处理 */
    private final InterceptorChain interceptorChain;
    /** 本地内存管理器，追加记忆内容 */
    private final MemoryManager memoryManager;
    /** 记忆服务 Feign 客户端，远程持久化感知数据 */
    private final MemoryFeignClient memoryFeignClient;

    /**
     * 构造响应构建阶段。
     *
     * @param interceptorChain  拦截器链
     * @param memoryManager     本地内存管理器（可为 null）
     * @param memoryFeignClient 记忆服务客户端
     */
    public ResponseBuildStage(InterceptorChain interceptorChain,
                              @org.springframework.lang.Nullable MemoryManager memoryManager,
                              MemoryFeignClient memoryFeignClient) {
        this.interceptorChain = interceptorChain;
        this.memoryManager = memoryManager;
        this.memoryFeignClient = memoryFeignClient;
    }

    /**
     * 处理响应构建流程。根据上下文状态分三路：
     * 1. 已消费的流式内容（__stream_consumed__=true）-> 直接持久化
     * 2. 有 Flux 对象（__stream_flux__）-> 装饰流式 Flux，完成时持久化
     * 3. 其他 -> 同步模式下从消息列表提取最后的 assistant 消息
     *
     * @param context 聊天上下文
     * @param chain   流水线责任链
     */
    @Override
    public void process(ChatContext context, Chain chain) {
        // 流式内容已由 ToolCallLoopStage 消费完毕，直接持久化
        if (Boolean.TRUE.equals(context.getAttribute("__stream_consumed__"))) {
            handleSyncPersistDirect(context);
            chain.next(context);
            return;
        }

        // 存在流式 Flux，装饰它以便在完成时自动持久化
        Object fluxObj = context.getAttribute("__stream_flux__");
        if (fluxObj instanceof Flux) {
            handleStream(context, (Flux<String>) fluxObj);
        } else {
            // 同步模式：从消息列表中提取最后的 assistant 消息
            handleSync(context);
        }
        chain.next(context);
    }

    /**
     * 处理已消费流式内容的直接持久化。
     * 从上下文属性中读取完整文本，若包含原始 JSON 格式则先提取纯文本。
     */
    private void handleSyncPersistDirect(ChatContext context) {
        String fullContent = (String) context.getAttribute("__stream_full_content__");
        if (fullContent == null || fullContent.isEmpty()) {
            log.warn("[ResponseBuildStage] __stream_full_content__ is empty, skipping build");
            return;
        }
        // 检测是否为原始 API JSON 格式（包含 SSE data 或 choices 字段）
        if (fullContent.contains("data:{") || fullContent.contains("\"choices\"")) {
            String extracted = extractPlainText(fullContent);
            if (!extracted.isEmpty()) {
                fullContent = extracted;
            }
        }
        buildAndPersist(context, fullContent);
    }

    /**
     * 装饰流式 Flux：在流完成时自动触发持久化。
     * 仅在流正常完成时执行，错误时记录日志但不中断管线。
     */
    @SuppressWarnings("unchecked")
    private void handleStream(ChatContext context, Flux<String> streamFlux) {
        Flux<String> decorated = streamFlux
                .doOnComplete(() -> {
                    // 流完成后，从上下文读取累积的完整内容并持久化
                    String fullContent = (String) context.getAttribute("__stream_full_content__");
                    if (fullContent != null && !fullContent.isEmpty()) {
                        if (fullContent.contains("data:{") || fullContent.contains("\"choices\"")) {
                            String extracted = extractPlainText(fullContent);
                            if (!extracted.isEmpty()) fullContent = extracted;
                        }
                        buildAndPersist(context, fullContent);
                    }
                })
                .doOnError(error -> {
                    log.error("[ResponseBuildStage] Stream chat failed", error);
                    if (memoryManager != null) {
                        memoryManager.append("[Stream chat failed] " + error.getMessage());
                    }
                });
        // 将装饰后的 Flux 回写到上下文，供上游消费者使用
        context.setAttribute("__stream_flux__", decorated);
    }

    /**
     * 同步模式处理：从消息列表中查找最后一条 assistant 消息作为响应文本。
     */
    private void handleSync(ChatContext context) {
        String responseText = extractLastAssistantMessage(context);
        buildAndPersist(context, responseText);
    }

    /**
     * 构建 ChatResult 并持久化到会话和记忆系统。
     *
     * 流程：
     * 1. 标记追踪结束时间，计算耗时
     * 2. 构建 ChatResult（包含 token 用量和耗时）
     * 3. 执行拦截器 postHandle 后处理
     * 4. 将 assistant 消息追加到会话
     * 5. 通过 MemoryManager 和 MemoryFeignClient 持久化记忆
     *
     * @param context 聊天上下文
     * @param content LLM 生成的响应文本
     */
    private void buildAndPersist(ChatContext context, String content) {
        context.getTracing().markEnd();
        String tokenUsage = (String) context.getAttribute("__stream_token_usage__");
        if (tokenUsage == null) tokenUsage = "prompt=0 completion=0 total=0";
        long durationMs = context.getTracing().getTotalDuration();

        // 构建 ChatResult 并设置到上下文中
        ChatResult result = new ChatResult(content, "stop", tokenUsage, Collections.emptyList(), durationMs);
        context.setResult(result);
        // 执行后置拦截器（如日志、审计）
        interceptorChain.postHandle(context, result);

        // 将 assistant 消息追加到会话历史
        ModelAdapter adapter = context.getModelProvider().getConfiguredAdapter();
        Message assistantMsg = Message.builder()
                .role("assistant")
                .content(content)
                .model(adapter != null ? adapter.getModel() : "unknown")
                .createdAt(LocalDateTime.now())
                .build();
        Session session = context.getSession();
        session.getMessages().add(assistantMsg);

        // 本地内存持久化
        if (memoryManager != null) {
            memoryManager.append(content);
        }

        // 远程记忆服务持久化（通过 Feign 客户端）
        if (memoryFeignClient != null) {
            try {
                PerceptionData perception = PerceptionData.builder()
                        .role("assistant")
                        .content(content)
                        .timestamp(System.currentTimeMillis())
                        .metadata(Map.of("sessionId", context.getRequest().getSessionId()))
                        .build();
                memoryFeignClient.ingest(perception, context.getRequest().getSessionId(), "default");
            } catch (Exception e) {
                log.warn("[ResponseBuildStage] MemoryFeignClient.ingest() failed: {}", e.getMessage());
            }
        }
    }

    /**
     * 从 ChatRequest 的消息列表中查找最后一条 assistant 角色的消息。
     *
     * @param context 聊天上下文
     * @return assistant 消息内容，不存在时返回空字符串
     */
    private String extractLastAssistantMessage(ChatContext context) {
        // 从尾部遍历消息列表，提高查找效率
        for (int i = context.getRequest().getMessages().size() - 1; i >= 0; i--) {
            Message msg = context.getRequest().getMessages().get(i);
            if ("assistant".equals(msg.getRole())) {
                return msg.getContent();
            }
        }
        return "";
    }

    /**
     * 从原始 SSE/JSON 响应文本中提取 "content" 字段的纯文本内容。
     * 使用正则匹配所有 \"content\":\"...\" 对，拼接后返回。
     *
     * @param raw 原始响应文本（可能包含 SSE data 前缀和 JSON 结构）
     * @return 提取的纯文本内容
     */
    private String extractPlainText(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder text = new StringBuilder();
        Pattern p = Pattern.compile("\"content\":\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = p.matcher(raw);
        while (m.find()) {
            String val = m.group(1);
            if (val != null && !val.isEmpty()) text.append(val);
        }
        return text.toString();
    }

    /** @return 阶段执行顺序，4 表示最后执行 */
    @Override
    public int getOrder() { return 4; }

    /** @return 阶段名称 */
    @Override
    public String getStageName() { return "ResponseBuild"; }
}