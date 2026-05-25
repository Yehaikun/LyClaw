package lyjew.com.lyclaw.reflect.impl.actor;

import static lyjew.com.lyclaw.react.SseEventTypes.MESSAGE;

import lyjew.com.lyclaw.annotation.reflect.Primitive;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.react.ToolExecutor;
import lyjew.com.lyclaw.reflect.model.ActorResult;
import lyjew.com.lyclaw.reflect.model.Issue;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.primitive.Actor;
import lyjew.com.lyclaw.reflect.topology.PrimitiveType;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lyjew.com.lyclaw.tool.ToolRegistry;
import org.springframework.http.codec.ServerSentEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 封装 {@link ReActEngine} 推理-行动循环的 Actor 原语实现。
 *
 * <p>核心增强：在执行 ReAct 之前，将上一轮反思产物（currentReflection + currentIssues）
 * 注入系统提示词，形成「反思反馈→重试」的闭环。工具调用记录会收集到 ActorResult.toolCalls
 * 供后续 Evaluator 分析工具级错误模式。
 *
 * <p>提示词增强格式：
 * <pre>
 *   [原始 systemPrompt]
 *   ---
 *   [REFLECTION FEEDBACK]
 *   上一轮问题: ...
 *   分析与建议: ...
 *   请在本次回复中修正以上问题。
 * </pre>
 */
@Primitive(type = PrimitiveType.ACTOR, name = "reAct", isDefault = true)
public class ReActActor implements Actor {

    private final ReActEngine reActEngine;
    private final ChatFacade chatFacade;
    private final ToolRegistry toolRegistry;

    public ReActActor(ReActEngine reActEngine, ChatFacade chatFacade, ToolRegistry toolRegistry) {
        this.reActEngine = reActEngine;
        this.chatFacade = chatFacade;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public ActorResult execute(ReflectionContext ctx) {
        String enhancedPrompt = buildEnhancedPrompt(ctx);
        List<ToolDefinition> tools = toolRegistry.getAllDefinitions();
        List<ActorResult.ToolCallRecord> toolRecords = new ArrayList<>();

        // 构建 ChatRequest：增强后的 system prompt + 用户原始消息 + 工具定义
        ChatRequest request = ChatRequest.builder()
                .systemPrompt(enhancedPrompt)
                .messages(new ArrayList<>(List.of(Message.user(ctx.getUserMessage()))))
                .tools(tools)
                .build();

        // 封装 ToolRegistry 为 ReActEngine 所需的 ToolExecutor 接口，同时记录调用结果
        ToolExecutor toolExecutor = (toolName, toolCallId, argumentsJson) -> {
            ToolExecutionResult result = toolRegistry.executeByName(toolName, toolCallId, argumentsJson, request);
            toolRecords.add(new ActorResult.ToolCallRecord(
                    toolName, toolCallId, argumentsJson,
                    result.isSuccess() ? result.getResult() : result.getError(),
                    result.isSuccess()));
            return result.isSuccess() ? result.getResult()
                    : "[TOOL_ERROR] " + result.getError();
        };

        String output = reActEngine.execute(chatFacade, request, toolExecutor);

        writebackMessages(ctx, request);

        ActorResult actorResult = new ActorResult(output);
        actorResult.setToolCalls(toolRecords);
        actorResult.setSuccessCount((int) toolRecords.stream()
                .filter(ActorResult.ToolCallRecord::isSuccess).count());
        actorResult.setFailCount((int) toolRecords.stream()
                .filter(r -> !r.isSuccess()).count());
        return actorResult;
    }

    /**
     * 流式执行 — 通过 ReActEngine.executeStream 实时推送 LLM 生成内容和工具调用事件。
     * 每块文本和工具事件立即通过 chunkSink 转发给上层（TopologyExecutor → SSE → 前端），
     * 同时收集完整输出文本和工具调用记录用于 ActorResult。
     */
    @Override
    public ActorResult executeStream(ReflectionContext ctx, Consumer<ServerSentEvent<String>> chunkSink) {
        String enhancedPrompt = buildEnhancedPrompt(ctx);
        List<ToolDefinition> tools = toolRegistry.getAllDefinitions();
        List<ActorResult.ToolCallRecord> toolRecords = new ArrayList<>();
        StringBuilder fullOutput = new StringBuilder();

        ChatRequest request = ChatRequest.builder()
                .systemPrompt(enhancedPrompt)
                .messages(new ArrayList<>(List.of(Message.user(ctx.getUserMessage()))))
                .tools(tools)
                .stream(true)  // 启用SSE流式，逐token返回
                .build();

        ToolExecutor toolExecutor = (toolName, toolCallId, argumentsJson) -> {
            ToolExecutionResult result = toolRegistry.executeByName(toolName, toolCallId, argumentsJson, request);
            toolRecords.add(new ActorResult.ToolCallRecord(
                    toolName, toolCallId, argumentsJson,
                    result.isSuccess() ? result.getResult() : result.getError(),
                    result.isSuccess()));
            return result.isSuccess() ? result.getResult()
                    : "[TOOL_ERROR] " + result.getError();
        };

        // 流式执行：逐token推送到 chunkSink，同时收集 message 文本到 fullOutput
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("ReAct流式调用被取消");
            }
            reActEngine.executeStream(chatFacade, request, toolExecutor)
                    .doOnNext(sse -> {
                        chunkSink.accept(sse);
                        if (MESSAGE.equals(sse.event()) && sse.data() != null) {
                            fullOutput.append(sse.data());
                        }
                    })
                    .blockLast(Duration.ofMinutes(5));
        } catch (Exception e) {
            if (isInterrupted(e)) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("ReAct流式调用被取消", e);
            }
            throw new RuntimeException("ReAct流式执行失败", e);
        }

        String output = fullOutput.toString();
        if (output.isEmpty()) {
            output = ctx.getCurrentOutput();
        }

        writebackMessages(ctx, request);

        ActorResult actorResult = new ActorResult(output);
        actorResult.setToolCalls(toolRecords);
        actorResult.setSuccessCount((int) toolRecords.stream()
                .filter(ActorResult.ToolCallRecord::isSuccess).count());
        actorResult.setFailCount((int) toolRecords.stream()
                .filter(r -> !r.isSuccess()).count());
        return actorResult;
    }

    private boolean isInterrupted(Throwable err) {
        Throwable cause = err;
        while (cause != null) {
            if (cause instanceof InterruptedException) return true;
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * 将本 Actor 内部 ChatRequest 中积累的工具调用消息回写到管线 AgentContext 的 ChatRequest，
     * 确保 {@code SessionPersistenceHook} 能持久化完整的对话历史（含工具调用）。
     */
    private void writebackMessages(ReflectionContext ctx, ChatRequest internalRequest) {
        ChatRequest parent = ctx.getChatRequest();
        if (parent == null) return;
        List<Message> parentMessages = parent.getMessages();
        List<Message> childMessages = internalRequest.getMessages();
        if (childMessages.size() <= 1) return; // 只有用户消息，无需回写
        // 替换父请求的消息列表为子请求的完整消息（子请求首条同样是用户消息）
        parentMessages.clear();
        parentMessages.addAll(childMessages);
    }

    /**
     * 构建包含反思反馈的增强系统提示词。
     * 仅在上下文中存在 currentIssues 或 currentReflection 时附加反馈段落。
     */
    private String buildEnhancedPrompt(ReflectionContext ctx) {
        StringBuilder sb = new StringBuilder();
        String original = ctx.getSystemPrompt();
        if (original != null && !original.isEmpty()) {
            sb.append(original).append("\n\n");
        }

        List<Issue> issues = ctx.getCurrentIssues();
        String reflection = ctx.getCurrentReflection();

        boolean hasFeedback = (issues != null && !issues.isEmpty())
                || (reflection != null && !reflection.isEmpty());

        if (hasFeedback) {
            sb.append("---\n[REFLECTION FEEDBACK]\n");

            if (issues != null && !issues.isEmpty()) {
                sb.append("上一轮尝试存在以下问题：\n");
                for (Issue issue : issues) {
                    sb.append("- [").append(issue.getSeverity()).append("] ")
                            .append(issue.getCategory()).append(": ")
                            .append(issue.getDescription()).append("\n");
                }
                sb.append("\n");
            }

            if (reflection != null && !reflection.isEmpty()) {
                sb.append("分析与改进建议：\n").append(reflection).append("\n\n");
            }

            sb.append("请在本次回复中修正以上问题。\n---\n");
        }

        return sb.toString();
    }
}
