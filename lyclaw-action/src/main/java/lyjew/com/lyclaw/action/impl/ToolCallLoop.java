package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.react.ReActMessageHook;
import lyjew.com.lyclaw.tool.ToolCallPolicy;
import lyjew.com.lyclaw.tool.ToolErrorAction;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 工具调用循环处理器，实现 LLM Agent 的 ReAct（推理-行动）循环。
 *
 * <p>每轮循环产生新消息时通过{@link ReActMessageHook}回调通知，
 * 使用方（如SessionManager）可将消息持久化、采集指标、触发审计等。
 * ToolCallLoop本身不感知持久化实现。</p>
 */
@Slf4j
@Component
public class ToolCallLoop {

    private final ChatFacade chatFacade;
    private final ToolRegistry toolRegistry;
    private final ToolCallPolicy toolCallPolicy;
    private final List<ReActMessageHook> messageHooks;

    public ToolCallLoop(ChatFacade chatFacade,
                        ToolRegistry toolRegistry,
                        ToolCallPolicy toolCallPolicy,
                        List<ReActMessageHook> messageHooks) {
        this.chatFacade = chatFacade;
        this.toolRegistry = toolRegistry;
        this.toolCallPolicy = toolCallPolicy;
        this.messageHooks = messageHooks != null ? messageHooks : List.of();
    }

    public ChatResult execute(ChatContext context) {
        beforeLoop(context);

        List<Message> messages = context.getRequest().getMessages();
        String sessionId = context.getSessionId();

        // 钩子点1: 用户消息（进入循环前已在messages中）
        notifyHooks(sessionId, findLastUserMessage(messages));

        int round = 0;
        int maxRounds = toolCallPolicy.getMaxRounds();

        while (round < maxRounds) {
            ModelResponse response;
            try {
                response = chatFacade.chat(context.getRequest());
            } catch (Exception e) {
                log.warn("ChatFacade chat failed: {}", e.getMessage());
                return new ChatResult("Tool execution unavailable - LLM call failed", "stop", null, null, 0L);
            }

            if (!handleModelResponse(response)) {
                Message assistantMsg = Message.builder()
                        .role("assistant")
                        .content(response.getContent() != null ? response.getContent() : "")
                        .build();
                messages.add(assistantMsg);
                notifyHooks(sessionId, assistantMsg);  // 钩子点2: 最终assistant消息
                break;
            }

            List<ToolCall> calls = convertToolCalls(response);
            Message assistantMsg = Message.builder()
                    .role("assistant")
                    .content(response.getContent() != null ? response.getContent() : "")
                    .toolCalls(calls)
                    .build();
            messages.add(assistantMsg);
            notifyHooks(sessionId, assistantMsg);  // 钩子点3: 含tool_calls的assistant消息

            boolean shouldAbort = false;
            for (ModelResponse.ToolCallRequest req : response.getToolCalls()) {
                try {
                    ToolExecutionResult result = toolRegistry.execute(buildToolCall(req), context);

                    Message toolMsg = Message.builder()
                            .role("tool")
                            .toolCallId(req.getId())
                            .content(result.isSuccess() ? result.getResult() : result.getError())
                            .build();
                    messages.add(toolMsg);
                    notifyHooks(sessionId, toolMsg);  // 钩子点4: 工具执行结果

                    log.debug("工具执行完成: tool={}, success={}", req.getName(), result.isSuccess());
                } catch (Exception e) {
                    ToolCall toolCall = buildToolCall(req);
                    ToolErrorAction action = toolCallPolicy.handleToolError(toolCall, e, context);

                    log.warn("工具执行异常: tool={}, error={}, action={}",
                            req.getName(), e.getMessage(), action);

                    Message errorMsg = Message.builder()
                            .role("tool")
                            .toolCallId(req.getId())
                            .content("Error: " + e.getMessage())
                            .build();
                    messages.add(errorMsg);
                    notifyHooks(sessionId, errorMsg);  // 钩子点4b: 工具异常结果

                    if (action == ToolErrorAction.ABORT) {
                        context.setAttribute("error", e.getMessage());
                        shouldAbort = true;
                        break;
                    }
                }
            }

            if (shouldAbort) break;
            round++;

            if (!toolCallPolicy.shouldContinue(context, round)) {
                log.info("达到策略上限，终止循环: round={}, maxRounds={}", round, maxRounds);
                break;
            }
        }

        String responseText = extractLastAssistantMessage(context);
        ChatResult result = new ChatResult(
                responseText,
                "stop",
                "prompt=0 completion=0 total=0",
                Collections.emptyList(),
                0L
        );

        afterLoop(context, result);
        return result;
    }

    /**
     * 通知所有已注册的消息钩子。
     * 每个钩子必须快速返回（O(1)内存操作），耗时操作在钩子内部异步化。
     */
    private void notifyHooks(String sessionId, Message message) {
        if (message == null || messageHooks.isEmpty()) return;
        for (ReActMessageHook hook : messageHooks) {
            hook.onMessage(sessionId, message);
        }
    }

    /** 在消息列表中找到最后一条用户消息 */
    private Message findLastUserMessage(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                return messages.get(i);
            }
        }
        return null;
    }

    protected void beforeLoop(ChatContext context) {
    }

    protected void afterLoop(ChatContext context, ChatResult result) {
    }

    protected boolean handleModelResponse(ModelResponse response) {
        return response.hasToolCalls();
    }

    private List<ToolCall> convertToolCalls(ModelResponse response) {
        if (!response.hasToolCalls()) {
            return Collections.emptyList();
        }
        List<ToolCall> result = new ArrayList<>();
        for (ModelResponse.ToolCallRequest req : response.getToolCalls()) {
            result.add(buildToolCall(req));
        }
        return result;
    }

    private ToolCall buildToolCall(ModelResponse.ToolCallRequest req) {
        return ToolCall.builder()
                .toolCallId(req.getId())
                .name(req.getName())
                .arguments(req.getArguments())
                .build();
    }

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
