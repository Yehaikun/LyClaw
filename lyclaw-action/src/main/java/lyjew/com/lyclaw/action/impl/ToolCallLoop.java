package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.tool.ToolCallPolicy;
import lyjew.com.lyclaw.tool.ToolErrorAction;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class ToolCallLoop {

    private final ModelProvider modelProvider;
    private final ToolRegistry toolRegistry;
    private final ToolCallPolicy toolCallPolicy;

    public ToolCallLoop(ModelProvider modelProvider,
                        ToolRegistry toolRegistry,
                        ToolCallPolicy toolCallPolicy) {
        this.modelProvider = modelProvider;
        this.toolRegistry = toolRegistry;
        this.toolCallPolicy = toolCallPolicy;
    }

    public ChatResult execute(ChatContext context) {
        beforeLoop(context);

        ModelAdapter adapter = modelProvider.getConfiguredAdapter();
        if (adapter == null) {
            log.warn("No configured adapter available for tool call loop");
            return new ChatResult("Tool execution unavailable - no LLM configured", "stop", null, null, 0L);
        }
        List<Message> messages = context.getRequest().getMessages();

        int round = 0;
        int maxRounds = toolCallPolicy.getMaxRounds();

        while (round < maxRounds) {
            ModelResponse response = adapter.chat(context.getRequest());

            if (!handleModelResponse(response)) {
                messages.add(Message.builder()
                        .role("assistant")
                        .content(response.getContent() != null ? response.getContent() : "")
                        .build());
                break;
            }

            List<ToolCall> calls = convertToolCalls(response);
            messages.add(Message.builder()
                    .role("assistant")
                    .content(response.getContent() != null ? response.getContent() : "")
                    .toolCalls(calls)
                    .build());

            boolean shouldAbort = false;
            for (ModelResponse.ToolCallRequest req : response.getToolCalls()) {
                try {
                    lyjew.com.lyclaw.tool.ToolResult result =
                            toolRegistry.execute(buildToolCall(req), context);

                    messages.add(Message.builder()
                            .role("tool")
                            .toolCallId(req.getId())
                            .content(result.isSuccess() ? result.getResult() : result.getError())
                            .build());

                    log.debug("工具执行完成: tool={}, success={}", req.getName(), result.isSuccess());
                } catch (Exception e) {
                    ToolCall toolCall = buildToolCall(req);
                    ToolErrorAction action = toolCallPolicy.handleToolError(toolCall, e, context);

                    log.warn("工具执行异常: tool={}, error={}, action={}",
                            req.getName(), e.getMessage(), action);

                    messages.add(Message.builder()
                            .role("tool")
                            .toolCallId(req.getId())
                            .content("Error: " + e.getMessage())
                            .build());

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
