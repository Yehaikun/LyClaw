package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.context.ContextBuilder;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;
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
@Component
public class ContextBuildStage implements PipelineStage {

    /** 上下文构建策略 */
    private final ContextBuilder contextBuilder;

    public ContextBuildStage(ContextBuilder contextBuilder) {
        this.contextBuilder = contextBuilder;
    }

    @Override
    public void process(ChatContext context, Chain chain) {
        // 读取 ChatContext 中已注入的数据
        MemoryContent memory = context.getMemory();
        List<ToolDefinition> toolDefinitions = context.getToolDefinitions();

        // 调用 ContextBuilder 策略构建消息列表
        List<Message> builtMessages = contextBuilder.buildContext(
                context.getSession(), memory, toolDefinitions);

        // 将构建好的消息列表写回 ChatContext（替换会话原始消息）
//        context.getMessages().clear();
//        context.getMessages().addAll(builtMessages);
        context.getRequest().setMessages(builtMessages);

        chain.next(context);
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