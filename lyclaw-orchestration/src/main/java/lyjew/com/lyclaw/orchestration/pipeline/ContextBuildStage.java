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

/**
 * 上下文构建阶段（同步管线）。
 *
 * 通过 ContextBuilder 组装系统提示、记忆内容和会话历史。
 * 同时调用 MemoryFeignClient 进行远程记忆检索（topK=10），
 * 通过上下文增强 LLM 的回复质量。
 * 执行顺序为 0（管线第一阶段）。
 */
@Slf4j
public class ContextBuildStage implements PipelineStage {

    private final ContextBuilder contextBuilder;
    private final MemoryFeignClient memoryFeignClient;

    public ContextBuildStage(ContextBuilder contextBuilder, MemoryFeignClient memoryFeignClient) {
        this.contextBuilder = contextBuilder;
        this.memoryFeignClient = memoryFeignClient;
        log.info("[ContextBuildStage] Initialized with contextBuilder={}", contextBuilder.getClass().getSimpleName());
    }

    /**
     * 执行上下文构建：
     * 1. 通过 Feign 客户端检索相关记忆
     * 2. 调用 ContextBuilder 构建完整消息列表
     * 3. 提取系统提示词并注入到 ChatRequest
     * 4. 注入工具定义到 ChatRequest
     *
     * @param context 聊天上下文
     * @param chain   管线链
     */
    @Override
    public void process(ChatContext context, Chain chain) {
        log.info("[ContextBuildStage] Starting context build...");

        MemoryContent memory = context.getMemory();
        List<ToolDefinition> toolDefinitions = context.getToolDefinitions();

        log.info("[ContextBuildStage] Input: memory={} chars, toolDefs={}, sessionMsgs={}",
                memory != null ? memory.getContent().length() : 0,
                toolDefinitions != null ? toolDefinitions.size() : 0,
                context.getSession().getMessages().size());

        // 远程记忆检索：通过 Feign 客户端从 memory 服务获取相关记忆
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
                        // 将检索结果存入上下文属性，供后续阶段使用
                        context.setAttribute("__memory_retrieval_result__", memoryResult);
                    }
                }
            } catch (Exception e) {
                log.warn("[ContextBuildStage] MemoryFeignClient retrieval failed: {}", e.getMessage());
            }
        }

        // 构建完整的上下文消息列表
        List<Message> builtMessages = contextBuilder.buildContext(
                context.getSession(), memory, toolDefinitions);

        log.info("[ContextBuildStage] ContextBuilder built {} messages", builtMessages.size());

        // 提取系统提示词并单独设置到 ChatRequest
        String systemPrompt = extractSystemPrompt(builtMessages);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            context.getRequest().setSystemPrompt(systemPrompt);
            log.info("[ContextBuildStage] Set systemPrompt ({} chars) from system message", systemPrompt.length());
            // 从消息列表中移除系统消息（已单独设置）
            builtMessages.removeIf(m -> "system".equals(m.getRole()));
        }

        // 将构建好的消息列表写入上下文
        context.getMessages().clear();
        context.getMessages().addAll(builtMessages);
        context.getRequest().setMessages(builtMessages);

        // 注入工具定义
        context.getRequest().setTools(toolDefinitions);
        log.info("[ContextBuildStage] Injected {} tool definitions into ChatRequest",
                toolDefinitions != null ? toolDefinitions.size() : 0);

        log.info("[ContextBuildStage] Completed");
        chain.next(context);
    }

    /**
     * 从消息列表中提取系统消息的内容。
     */
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
