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

/**
 * 工具调用循环处理器，实现 LLM Agent 的 ReAct（推理-行动）循环。
 *
 * <p>该类负责驱动多轮工具调用对话流程：
 * <ol>
 *   <li>调用 LLM 获取初始响应（可能包含工具调用指令）</li>
 *   <li>如果响应中包含工具调用，依次执行各工具，并将结果反馈给 LLM</li>
 *   <li>LLM 根据工具执行结果继续推理，可能产生新的工具调用或最终文本回复</li>
 *   <li>循环持续直到 LLM 不再请求工具调用、达到最大轮数、或出现致命错误</li>
 * </ol>
 * </p>
 *
 * <p>循环受 {@link ToolCallPolicy} 控制，可配置最大轮数、错误处理策略（重试/跳过/中止）。
 * 提供了 {@link #beforeLoop} 和 {@link #afterLoop} 钩子供子类扩展。</p>
 *
 * @see ToolCallPolicy
 * @see ModelProvider
 */
@Slf4j
@Component
public class ToolCallLoop {

    /** 模型提供者，用于获取配置好的 LLM 适配器 */
    private final ModelProvider modelProvider;
    /** 工具注册表，用于执行工具调用 */
    private final ToolRegistry toolRegistry;
    /** 工具调用控制策略 */
    private final ToolCallPolicy toolCallPolicy;

    /**
     * 构造函数，通过依赖注入初始化各组件。
     */
    public ToolCallLoop(ModelProvider modelProvider,
                        ToolRegistry toolRegistry,
                        ToolCallPolicy toolCallPolicy) {
        this.modelProvider = modelProvider;
        this.toolRegistry = toolRegistry;
        this.toolCallPolicy = toolCallPolicy;
    }

    /**
     * 执行完整的工具调用循环。
     *
     * <p>循环逻辑：
     * <ol>
     *   <li>调用 {@link #beforeLoop} 钩子</li>
     *   <li>获取已配置的 LLM 适配器，如不可用则返回错误</li>
     *   <li>进入循环：调用 LLM -> 检查是否有工具调用 -> 无则退出</li>
     *   <li>有工具调用：将 assistant 消息（含 tool_calls）追加到上下文</li>
     *   <li>依次执行每个工具调用，将 tool 消息（含执行结果）追加到上下文</li>
     *   <li>根据 {@link ToolErrorAction} 决定是否中止</li>
     *   <li>轮数递增，检查策略是否允许继续</li>
     *   <li>提取最后一条 assistant 消息作为最终响应</li>
     *   <li>调用 {@link #afterLoop} 钩子</li>
     * </ol>
     * </p>
     *
     * @param context 对话上下文
     * @return 最终聊天结果
     */
    public ChatResult execute(ChatContext context) {
        beforeLoop(context);

        ModelAdapter adapter = modelProvider.getConfiguredAdapter();
        if (adapter == null) {
            log.warn("No configured adapter available for tool call loop");
            return new ChatResult("Tool execution unavailable - no LLM configured", "stop", null, null, 0L);
        }
        // 获取当前消息列表的引用，后续操作直接修改此列表
        List<Message> messages = context.getRequest().getMessages();

        int round = 0;
        int maxRounds = toolCallPolicy.getMaxRounds();

        while (round < maxRounds) {
            // 1. 调用 LLM
            ModelResponse response = adapter.chat(context.getRequest());

            // 2. 检查响应中是否包含工具调用请求
            if (!handleModelResponse(response)) {
                // 无工具调用：追加 assistant 消息，结束循环
                messages.add(Message.builder()
                        .role("assistant")
                        .content(response.getContent() != null ? response.getContent() : "")
                        .build());
                break;
            }

            // 3. 将 assistant 消息（含 tool_calls）追加到上下文
            List<ToolCall> calls = convertToolCalls(response);
            messages.add(Message.builder()
                    .role("assistant")
                    .content(response.getContent() != null ? response.getContent() : "")
                    .toolCalls(calls)
                    .build());

            // 4. 执行每个工具调用
            boolean shouldAbort = false;
            for (ModelResponse.ToolCallRequest req : response.getToolCalls()) {
                try {
                    lyjew.com.lyclaw.tool.ToolResult result =
                            toolRegistry.execute(buildToolCall(req), context);

                    // 追加工具执行结果消息
                    messages.add(Message.builder()
                            .role("tool")
                            .toolCallId(req.getId())
                            .content(result.isSuccess() ? result.getResult() : result.getError())
                            .build());

                    log.debug("工具执行完成: tool={}, success={}", req.getName(), result.isSuccess());
                } catch (Exception e) {
                    ToolCall toolCall = buildToolCall(req);
                    // 根据策略决定错误处理方式
                    ToolErrorAction action = toolCallPolicy.handleToolError(toolCall, e, context);

                    log.warn("工具执行异常: tool={}, error={}, action={}",
                            req.getName(), e.getMessage(), action);

                    messages.add(Message.builder()
                            .role("tool")
                            .toolCallId(req.getId())
                            .content("Error: " + e.getMessage())
                            .build());

                    // ABORT 策略：记录错误并中断循环
                    if (action == ToolErrorAction.ABORT) {
                        context.setAttribute("error", e.getMessage());
                        shouldAbort = true;
                        break;
                    }
                }
            }

            if (shouldAbort) break;
            round++;

            // 检查策略是否允许继续下一轮
            if (!toolCallPolicy.shouldContinue(context, round)) {
                log.info("达到策略上限，终止循环: round={}, maxRounds={}", round, maxRounds);
                break;
            }
        }

        // 提取最终响应文本
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
     * 循环开始前的钩子方法，子类可覆写以进行预处理。
     *
     * @param context 对话上下文
     */
    protected void beforeLoop(ChatContext context) {
    }

    /**
     * 循环结束后的钩子方法，子类可覆写以进行后处理。
     *
     * @param context 对话上下文
     * @param result  最终聊天结果
     */
    protected void afterLoop(ChatContext context, ChatResult result) {
    }

    /**
     * 判断模型响应是否需要执行工具调用。
     *
     * <p>默认实现检查响应中是否包含 toolCalls。
     * 子类可覆写以添加额外判断逻辑。</p>
     *
     * @param response 模型响应
     * @return true 表示需要执行工具调用，false 表示循环结束
     */
    protected boolean handleModelResponse(ModelResponse response) {
        return response.hasToolCalls();
    }

    /**
     * 将 ModelResponse 中的工具调用请求转换为 ToolCall 列表。
     *
     * @param response 模型响应
     * @return ToolCall 列表（无工具调用时返回空列表）
     */
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

    /**
     * 将单个 ToolCallRequest 转换为 ToolCall 对象。
     *
     * @param req 工具调用请求
     * @return ToolCall 对象
     */
    private ToolCall buildToolCall(ModelResponse.ToolCallRequest req) {
        return ToolCall.builder()
                .toolCallId(req.getId())
                .name(req.getName())
                .arguments(req.getArguments())
                .build();
    }

    /**
     * 从上下文消息列表中提取最后一条 assistant 消息的内容。
     *
     * <p>从消息列表末尾向前搜索，找到第一条 role=assistant 的消息。</p>
     *
     * @param context 对话上下文
     * @return assistant 消息的文本内容，未找到时返回空字符串
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
