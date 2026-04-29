package lyjew.com.lyclaw.tool.impl;

import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.tool.ToolCallPolicy;
import lyjew.com.lyclaw.tool.ToolErrorAction;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.tool.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 工具调用循环 —— 模板方法模式封装"模型调用→工具执行→再次调用模型"的循环逻辑。
 *
 * <p><b>核心流程</b>：
 * <pre>
 * beforeLoop(context)                        ← 钩子 1
 * loop {
 *     ModelResponse resp = adapter.chat(req) ← 调用模型
 *     if (!handleModelResponse(resp)) break   ← 钩子 2：无工具调用则退出
 *     对每个 toolCall：
 *         ToolResult = toolRegistry.execute(toolCall, context)
 *         将结果注入 req.messages
 *     if (!policy.shouldContinue(context, round)) break
 * }
 * afterLoop(context, result)                  ← 钩子 3
 * </pre>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ModelProvider
 * @see ToolRegistry
 * @see ToolCallPolicy
 */
public class ToolCallLoop {

    /** 模型提供商 —— 获取已配置的 ModelAdapter */
    protected final ModelProvider modelProvider;

    /** 工具注册表 —— 按名称查找并执行工具 */
    protected final ToolRegistry toolRegistry;

    /** 工具调用策略 —— 控制轮次上限和重试逻辑 */
    protected final ToolCallPolicy toolCallPolicy;

    /**
     * 构造工具调用循环。
     *
     * @param modelProvider   模型提供商
     * @param toolRegistry    工具注册表
     * @param toolCallPolicy  工具调用策略
     */
    public ToolCallLoop(ModelProvider modelProvider,
                        ToolRegistry toolRegistry,
                        ToolCallPolicy toolCallPolicy) {
        this.modelProvider = modelProvider;
        this.toolRegistry = toolRegistry;
        this.toolCallPolicy = toolCallPolicy;
    }

    /**
     * 模板方法 —— 执行工具调用循环。
     *
     * @param context 对话上下文
     * @return 最终的 ChatResult（由子类 afterLoop 构建）
     */
    public ChatResult execute(ChatContext context) {
        // 钩子 1：循环开始前的准备工作（子类可扩展）
        beforeLoop(context);

        // 获取模型适配器
        ModelAdapter adapter = modelProvider.getConfiguredAdapter();
        // 获取可变消息列表
        List<Message> messages = context.getRequest().getMessages();

        int round = 0;
        while (round < toolCallPolicy.getMaxRounds()) {
            // 调用模型
            ModelResponse response = adapter.chat(context.getRequest());

            // 钩子 2：处理模型响应，返回 false 表示退出循环
            if (!handleModelResponse(response)) {
                // 无工具调用 —— 将模型回复写入消息列表后退出
                messages.add(Message.builder()
                        .role("assistant")
                        .content(response.getContent() != null
                                ? response.getContent() : "")
                        .build());
                break;
            }

            // 有工具调用 —— 将模型回复（含 toolCalls）写入消息列表
            messages.add(Message.builder()
                    .role("assistant")
                    .content(response.getContent() != null
                            ? response.getContent() : "")
                    .toolCalls(convertToolCalls(response))
                    .build());

            // 执行每个工具调用
            boolean shouldAbort = false;
            for (ModelResponse.ToolCallRequest req : response.getToolCalls()) {
                try {
                    // 将 ToolCallRequest 转为 ToolCall 然后执行
                    ToolCall toolCall = ToolCall.builder()
                            .toolCallId(req.getId())
                            .name(req.getName())
                            .arguments(req.getArguments())
                            .build();
                    ToolResult result = toolRegistry.execute(toolCall, context);

                    // 将工具执行结果写入消息列表
                    messages.add(Message.builder()
                            .role("tool")
                            .content(result.isSuccess() ? result.getResult() : result.getError())
                            .build());
                } catch (Exception e) {
                    // 回调策略决定如何处理错误
                    ToolErrorAction action = toolCallPolicy.handleToolError(
                            null, e, context);
                    if (action == ToolErrorAction.ABORT) {
                        messages.add(Message.builder()
                                .role("tool")
                                .content("Error: " + e.getMessage())
                                .build());
                        context.setAttribute("error", e.getMessage());
                        shouldAbort = true;
                        break;
                    }
                }
            }

            if (shouldAbort) break;

            round++;

            if (!toolCallPolicy.shouldContinue(context, round)) break;
        }

        // 构建结果
        String responseText = extractLastAssistantMessage(context);
        ChatResult result = new ChatResult(
                responseText, "stop", "prompt=0 completion=0 total=0",
                Collections.emptyList(), 0L
        );

        // 钩子 3：循环结束后收尾工作
        afterLoop(context, result);

        return result;
    }

    /**
     * 钩子方法 1 —— 循环开始前调用。子类可重写以执行准备工作（如初始化追踪）。
     *
     * @param context 对话上下文
     */
    protected void beforeLoop(ChatContext context) {
        // 默认不做任何事
    }

    /**
     * 钩子方法 2 —— 循环结束后调用。子类可重写以执行收尾工作（如更新指标）。
     *
     * @param context 对话上下文
     * @param result  对话处理结果
     */
    protected void afterLoop(ChatContext context, ChatResult result) {
        // 默认不做任何事
    }

    /**
     * 钩子方法 3 —— 判断模型响应是否包含工具调用。
     * 返回 false 表示没有工具调用，应退出循环。
     *
     * @param response 模型响应
     * @return true 表示有工具调用，应继续循环
     */
    protected boolean handleModelResponse(ModelResponse response) {
        return response.hasToolCalls();
    }

    /**
     * 将 ModelResponse 中的 ToolCallRequest 转换为 ToolCall 列表。
     *
     * @param response 模型响应
     * @return ToolCall 列表，无工具调用时返回空列表
     */
    private List<ToolCall> convertToolCalls(ModelResponse response) {
        if (!response.hasToolCalls()) {
            return null;
        }
        List<ToolCall> result = new ArrayList<>();
        for (ModelResponse.ToolCallRequest req : response.getToolCalls()) {
            ToolCall toolCall = ToolCall.builder()
                    .toolCallId(req.getId())
                    .name(req.getName())
                    .arguments(req.getArguments())
                    .build();
            result.add(toolCall);
        }
        return result;
    }

    /**
     * 从消息列表中提取最后一条 assistant 消息的文本内容。
     *
     * @param context 对话上下文
     * @return AI 回复文本，无回复时返回空字符串
     */
    private String extractLastAssistantMessage(ChatContext context) {
        List<Message> messages = context.getRequest().getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("assistant".equals(messages.get(i).getRole())) {
                String content = messages.get(i).getContent();
                return content != null ? content : "";
            }
        }
        return "";
    }
}