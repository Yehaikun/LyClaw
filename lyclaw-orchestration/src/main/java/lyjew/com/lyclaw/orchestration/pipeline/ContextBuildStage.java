package lyjew.com.lyclaw.orchestration.pipeline;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.context.ContextBuilder;
import lyjew.com.lyclaw.feign.MemoryFeignClient;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.memory.MemoryQuery;
import lyjew.com.lyclaw.memory.MemoryQueryResult;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;
import java.util.List;

@Slf4j
public class ContextBuildStage implements PipelineStage {

    private final ContextBuilder contextBuilder;
    private final MemoryFeignClient memoryFeignClient;

    public ContextBuildStage(ContextBuilder contextBuilder, MemoryFeignClient memoryFeignClient) {
        this.contextBuilder = contextBuilder;
        this.memoryFeignClient = memoryFeignClient;
        log.info("[ContextBuildStage] Initialized with contextBuilder={}", contextBuilder.getClass().getSimpleName());
    }

    @Override
    public void process(ChatContext context, Chain chain) {
        log.info("[ContextBuildStage] Starting context build...");

        MemoryContent memory = context.getMemory();
        List<ToolDefinition> toolDefinitions = context.getToolDefinitions();

        log.info("[ContextBuildStage] Input: memory={} chars, toolDefs={}, sessionMsgs={}",
                memory != null ? memory.getContent().length() : 0,
                toolDefinitions != null ? toolDefinitions.size() : 0,
                context.getSession().getMessages().size());

        if (memoryFeignClient != null) {
            try {
                String query = context.getRequest().getLastUserMessage();
                if (query != null && !query.isEmpty()) {
                    MemoryQuery memoryQuery = MemoryQuery.builder()
                            .queryText(query)
                            .topK(10)
                            .build();
                    MemoryQueryResult memoryResult = memoryFeignClient.retrieve(memoryQuery);
                    if (memoryResult != null && memoryResult.getTotalHits() > 0) {
                        log.info("[ContextBuildStage] MemoryFeignClient retrieved {} entries in {}ms",
                                memoryResult.getTotalHits(), memoryResult.getQueryTimeMs());
                        context.setAttribute("__memory_retrieval_result__", memoryResult);
                    }
                }
            } catch (Exception e) {
                log.warn("[ContextBuildStage] MemoryFeignClient retrieval failed: {}", e.getMessage());
            }
        }

        List<Message> builtMessages = contextBuilder.buildContext(
                context.getSession(), memory, toolDefinitions);

        log.info("[ContextBuildStage] ContextBuilder built {} messages", builtMessages.size());

        String systemPrompt = extractSystemPrompt(builtMessages);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            context.getRequest().setSystemPrompt(systemPrompt);
            log.info("[ContextBuildStage] Set systemPrompt ({} chars) from system message", systemPrompt.length());
            builtMessages.removeIf(m -> "system".equals(m.getRole()));
        }

        context.getMessages().clear();
        context.getMessages().addAll(builtMessages);
        context.getRequest().setMessages(builtMessages);

        context.getRequest().setTools(toolDefinitions);
        log.info("[ContextBuildStage] Injected {} tool definitions into ChatRequest",
                toolDefinitions != null ? toolDefinitions.size() : 0);

        log.info("[ContextBuildStage] Completed");
        chain.next(context);
    }

    private String extractSystemPrompt(List<Message> messages) {
        for (Message msg : messages) {
            if ("system".equals(msg.getRole()) && msg.getContent() != null && !msg.getContent().isEmpty()) {
                return msg.getContent();
            }
        }
        return null;
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public String getStageName() {
        return "ContextBuild";
    }
}
