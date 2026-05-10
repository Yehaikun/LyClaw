package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.context.ContextBuilder;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Pipeline 第一阶段 —— 上下文构建阶段。
 *
 * <p>调用 ContextBuilder.buildContext() 构建模型输入的消息列表。
 * memory 和 toolDefinitions 在 ChatContext 构造时已经注入，
 * 本阶段只需要读取它们并调用 ContextBuilder 策略。</p>
 *
 * <p><b>设计动机</b>：ChatContext 的构造器要求 memory 和 toolDefinitions
 * 在创建时传入（它们是 final 字段），所以 ContextBuildStage 不需要自己
 * 去 MemoryManager/ToolRegistry 拿数据。它的职责就是选择 ContextBuilder
 * 策略并把已有数据组装成消息列表。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ContextBuilder
 */
@Slf4j
@Component
public class ContextBuildStage implements PipelineStage {

    /** 上下文构建策略 */
    private final ContextBuilder contextBuilder;

    public ContextBuildStage(ContextBuilder contextBuilder) {
        this.contextBuilder = contextBuilder;
        log.info("  [ContextBuildStage] 构造器: contextBuilder={}", contextBuilder.getClass().getSimpleName());
    }

    @Override
    public void process(ChatContext context, Chain chain) {
        log.info("  [ContextBuildStage] 开始：构建上下文...");

        // 读取 ChatContext 中已注入的数据
        MemoryContent memory = context.getMemory();
        List<ToolDefinition> toolDefinitions = context.getToolDefinitions();

        log.info("  [ContextBuildStage] 输入：记忆={} 字, 工具定义={} 个, 会话消息={} 条",
                memory != null ? memory.getContent().length() : 0,
                toolDefinitions != null ? toolDefinitions.size() : 0,
                context.getSession().getMessages().size());

        // 调用 ContextBuilder 策略构建消息列表
        List<Message> builtMessages = contextBuilder.buildContext(
                context.getSession(), memory, toolDefinitions);

        log.info("  [ContextBuildStage] ContextBuilder 构建了 {} 条消息", builtMessages.size());

        // Extract system message and set it as systemPrompt (so adapter properly handles it)
        // Fix Bug #2: DeepSeekOpenAIAdapter filters role="system" messages, so tool descriptions
        // must go through request.setSystemPrompt() instead of being a Message with role="system"
        String systemPrompt = extractSystemPrompt(builtMessages);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            context.getRequest().setSystemPrompt(systemPrompt);
            log.info("  [ContextBuildStage] Set systemPrompt ({} chars) from system message", systemPrompt.length());
            // Remove the system message from the list so adapter doesn't double-include it
            builtMessages.removeIf(m -> "system".equals(m.getRole()));
        }

        // 将构建好的消息列表写回 ChatRequest.messages（ToolCallLoopStage 实际读取的位置）
        // 同时更新 ChatContext.messages 保持一致性
        context.getMessages().clear();
        context.getMessages().addAll(builtMessages);
        context.getRequest().setMessages(builtMessages);

        // 将工具定义注入 ChatRequest.tools —— 适配器序列化请求时需要读取此字段
        // 如果缺少此操作，adapter.buildRequest() 不会在请求中发送 tools 数组给模型
        context.getRequest().setTools(toolDefinitions);
        log.info("  [ContextBuildStage] 注入工具定义到 ChatRequest: {} 个工具",
                toolDefinitions != null ? toolDefinitions.size() : 0);

        log.info("  [ContextBuildStage] 完成");
        chain.next(context);
    }

    /**
     * Extract system prompt content from the first system message in the built list.
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
        return 0; // 第一阶段
    }

    @Override
    public String getStageName() {
        return "ContextBuild";
    }
}