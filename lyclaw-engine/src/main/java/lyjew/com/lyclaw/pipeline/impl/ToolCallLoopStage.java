package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.error.ErrorPolicy;
import lyjew.com.lyclaw.error.ToolExecuteException;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.tool.ToolCallPolicy;
import lyjew.com.lyclaw.tool.ToolErrorAction;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Pipeline 第三阶段 —— 核心阶段：模型调用 + 工具执行循环。
 *
 * <p>循环流程：
 * <pre>
 * loop {
 *     1. ModelAdapter.chat(ChatRequest) → ModelResponse
 *     2. ModelResponse.hasToolCalls() = false → 退出循环
 *     3. 将 ModelResponse 转为 assistant 消息（含工具调用）写入 request.messages
 *     4. 对每个工具调用 → ToolRegistry.execute() → tool 消息写入 request.messages
 *     5. ToolCallPolicy.shouldContinue() → 决定是否继续
 * }
 * </pre>
 * </p>
 *
 * <p><b>核心数据流转</b>：
 * <ul>
 *   <li>adapter.chat() 接收 ChatRequest，返回 ModelResponse（不修改入参）</li>
 *   <li>本阶段负责将 ModelResponse 的内容写回 ChatRequest.getMessages()</li>
 *   <li>下一轮循环时，messages 中包含了历史 + 新的工具结果</li>
 * </ul>
 * </p>
 *
 * <p><b>ToolCallRequest 到 ToolCall 的转换</b>：
 * ModelResponse.ToolCallRequest（id/name/arguments）从模型返回，
 * 需要转换为 Message（role=assistant, toolCalls=...）写入消息列表。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ModelProvider
 * @see ToolCallPolicy
 * @see ErrorPolicy
 */
@Component
public class ToolCallLoopStage implements PipelineStage {

    private final ModelProvider modelProvider;
    private final ToolRegistry toolRegistry;
    private final ToolCallPolicy toolCallPolicy;
    private final ErrorPolicy errorPolicy;

    public ToolCallLoopStage(ModelProvider modelProvider,
                             ToolRegistry toolRegistry,
                             ToolCallPolicy toolCallPolicy,
                             ErrorPolicy errorPolicy) {
        this.modelProvider = modelProvider;
        this.toolRegistry = toolRegistry;
        this.toolCallPolicy = toolCallPolicy;
        this.errorPolicy = errorPolicy;
    }

    @Override
    public void process(ChatContext context, Chain chain) {
        ModelAdapter adapter = modelProvider.getConfiguredAdapter();
        // 获取请求中的消息列表，包一层 ArrayList 确保可变（调用方可能传入 List.of()）
        List<Message> messages = new ArrayList<>(context.getRequest().getMessages());
        context.getRequest().setMessages(messages);

        int round = 0;
        while (round < toolCallPolicy.getMaxRounds()) {
            // 调用模型 —— adapter.chat() 接收 ChatRequest，返回 ModelResponse
            ModelResponse response = adapter.chat(context.getRequest());

            // 将模型回复写入消息列表
            List<ToolCall> toolCalls = convertToolCallRequests(response);
            messages.add(Message.builder()
                    .role("assistant")
                    .content(response.getContent() != null ? response.getContent() : "")
                    .toolCalls(toolCalls)
                    .build());

            // 无工具调用 → 退出循环
            if (!response.hasToolCalls()) {
                break;
            }

            // 执行每个工具调用，将结果注入消息列表
            boolean shouldAbort = false;
            for (ModelResponse.ToolCallRequest req : response.getToolCalls()) {
                try {
                    // 将 ToolCallRequest 转为 ToolCall 执行
                    // 注：ToolRegistry.execute() 接收 lyclaw-common 的 ToolCall
                    ToolCall toolCall = ToolCall.builder()
                            .toolCallId(req.getId())  // ← 加上
                            .name(req.getName())
                            .arguments(req.getArguments())
                            .build();

                    ToolResult result = toolRegistry.execute(toolCall,context);

                    // 将工具执行结果以 tool 角色消息写入消息列表
                    messages.add(Message.builder()
                            .role("tool")
                            .content(result.isSuccess() ? result.getResult() : result.getError())
                            .build());
                } catch (Exception e) {
                    ToolErrorAction action = errorPolicy.onToolError(
                            null, ToolExecuteException.of(req.getName(), e), round);
                    if (action == ToolErrorAction.ABORT) {
                        messages.add(Message.builder()
                                .role("tool")
                                .content("Error: " + e.getMessage())
                                .build());
                        context.setAttribute("error", e.getMessage());
                        shouldAbort = true;
                        break;
                    }
                    if (action == ToolErrorAction.RETRY) {
                        // 跳过当前工具调用，继续循环
                        break;
                    }
                    // SKIP：将错误写入消息，让模型处理
                    messages.add(Message.builder()
                            .role("tool")
                            .content("Error: " + e.getMessage())
                            .build());
                }
            }

            if (shouldAbort) {
                break;
            }

            round++;

            if (!toolCallPolicy.shouldContinue(context, round)) {
                break;
            }
        }

        chain.next(context);
    }

    /**
     * 将 ModelResponse 中的 ToolCallRequest 列表转换为 lyclaw-common 的 ToolCall 列表。
     *
     * @param response 模型响应
     * @return ToolCall 列表，无工具调用时返回空列表
     */
    private List<ToolCall> convertToolCallRequests(ModelResponse response) {
        if (!response.hasToolCalls()) {
            return null;
        }
        List<ToolCall> result = new ArrayList<>();
        for (ModelResponse.ToolCallRequest req : response.getToolCalls()) {
            result.add(ToolCall.builder()
                    .toolCallId(req.getId())
                    .name(req.getName())
                    .arguments(req.getArguments())
                    .build());
        }
        return result;
    }

    @Override
    public int getOrder() {
        return 2; // 第三阶段
    }

    @Override
    public String getStageName() {
        return "ToolCallLoop";
    }
}