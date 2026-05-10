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

@Slf4j
@Component
public class ResponseBuildStage implements PipelineStage {

    private static final Pattern CONTENT_PATTERN = Pattern.compile("\"content\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final InterceptorChain interceptorChain;
    private final MemoryManager memoryManager;
    private final MemoryFeignClient memoryFeignClient;

    public ResponseBuildStage(InterceptorChain interceptorChain,
                              @org.springframework.lang.Nullable MemoryManager memoryManager,
                              MemoryFeignClient memoryFeignClient) {
        this.interceptorChain = interceptorChain;
        this.memoryManager = memoryManager;
        this.memoryFeignClient = memoryFeignClient;
    }

    @Override
    public void process(ChatContext context, Chain chain) {
        if (Boolean.TRUE.equals(context.getAttribute("__stream_consumed__"))) {
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

    private void handleSyncPersistDirect(ChatContext context) {
        String fullContent = (String) context.getAttribute("__stream_full_content__");
        if (fullContent == null || fullContent.isEmpty()) {
            log.warn("[ResponseBuildStage] __stream_full_content__ is empty, skipping build");
            return;
        }
        if (fullContent.contains("data:{") || fullContent.contains("\"choices\"")) {
            String extracted = extractPlainText(fullContent);
            if (!extracted.isEmpty()) {
                fullContent = extracted;
            }
        }
        buildAndPersist(context, fullContent);
    }

    @SuppressWarnings("unchecked")
    private void handleStream(ChatContext context, Flux<String> streamFlux) {
        Flux<String> decorated = streamFlux
                .doOnComplete(() -> {
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
        context.setAttribute("__stream_flux__", decorated);
    }

    private void handleSync(ChatContext context) {
        String responseText = extractLastAssistantMessage(context);
        buildAndPersist(context, responseText);
    }

    private void buildAndPersist(ChatContext context, String content) {
        context.getTracing().markEnd();
        String tokenUsage = (String) context.getAttribute("__stream_token_usage__");
        if (tokenUsage == null) tokenUsage = "prompt=0 completion=0 total=0";
        long durationMs = context.getTracing().getTotalDuration();

        ChatResult result = new ChatResult(content, "stop", tokenUsage, Collections.emptyList(), durationMs);
        context.setResult(result);
        interceptorChain.postHandle(context, result);

        ModelAdapter adapter = context.getModelProvider().getConfiguredAdapter();
        Message assistantMsg = Message.builder()
                .role("assistant")
                .content(content)
                .model(adapter != null ? adapter.getModel() : "unknown")
                .createdAt(LocalDateTime.now())
                .build();
        Session session = context.getSession();
        session.getMessages().add(assistantMsg);

        if (memoryManager != null) {
            memoryManager.append(content);
        }

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

    private String extractLastAssistantMessage(ChatContext context) {
        for (int i = context.getRequest().getMessages().size() - 1; i >= 0; i--) {
            Message msg = context.getRequest().getMessages().get(i);
            if ("assistant".equals(msg.getRole())) {
                return msg.getContent();
            }
        }
        return "";
    }

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

    @Override
    public int getOrder() { return 4; }

    @Override
    public String getStageName() { return "ResponseBuild"; }
}
