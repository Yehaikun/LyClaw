package lyjew.com.lyclaw.tool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.react.AgentContext;

/**
 * 工具执行管线默认实现，7 步流程：
 * resolve → policy → beforeHook → bind → invoke → afterHook → format。
 */
public class DefaultToolExecutionPipeline implements ToolExecutionPipeline {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolExecutionPipeline.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ToolRegistry toolRegistry;
    private final ToolCallPolicy toolCallPolicy;
    private final List<ToolHook> toolHooks;

    public DefaultToolExecutionPipeline(ToolRegistry toolRegistry,
                                         ToolCallPolicy toolCallPolicy,
                                         List<ToolHook> toolHooks) {
        this.toolRegistry = toolRegistry;
        this.toolCallPolicy = toolCallPolicy;
        this.toolHooks = toolHooks != null ? sortedHooks(toolHooks) : List.of();
    }

    private static List<ToolHook> sortedHooks(List<ToolHook> hooks) {
        List<ToolHook> sorted = new ArrayList<>(hooks);
        sorted.sort(Comparator.comparingInt(ToolHook::getOrder));
        return List.copyOf(sorted);
    }

    @Override
    public String execute(ToolCall toolCall, AgentContext ctx) {
        String toolName = toolCall.getName();
        String toolCallId = toolCall.getToolCallId();

        // Step 1: Resolve — 查找工具实例
        Tool tool = toolRegistry.get(toolName);
        if (tool == null) {
            log.warn("Tool not found: {}", toolName);
            return "Error: tool not found: " + toolName;
        }

        // Step 2: Policy check — 策略检查（频率限制、黑白名单）
        if (toolCallPolicy != null && !toolCallPolicy.canExecute(toolName, null)) {
            log.warn("Tool call blocked by policy: {}", toolName);
            return "Error: tool call blocked by policy: " + toolName;
        }

        // Step 3: beforeExecution hooks
        for (ToolHook hook : toolHooks) {
            try {
                hook.beforeExecution(toolCall, ctx);
            } catch (Exception e) {
                log.warn("ToolHook.beforeExecution failed: {}", e.getMessage(), e);
                return hook.onError(toolCall, e, ctx);
            }
        }

        // Step 4: Bind — 参数绑定
        Map<String, Object> args = parseArgs(toolCall.getArguments());

        // Step 5: Invoke — 实际执行
        String result;
        try {
            ToolExecutionResult execResult = toolRegistry.execute(toolCall, null);
            if (!execResult.isSuccess()) {
                execResult = toolRegistry.executeByName(toolName, toolCallId,
                        toolCall.getArguments(), ctx.getChatRequest());
            }
            result = execResult.isSuccess()
                    ? (execResult.getResult() != null ? execResult.getResult() : "")
                    : "Error: " + (execResult.getError() != null ? execResult.getError() : "unknown");

            if (execResult.isSuccess()) {
                ctx.getSuccessCount().incrementAndGet();
            } else {
                ctx.getFailCount().incrementAndGet();
            }
            ctx.addToolResult(execResult.isSuccess() ? execResult.getResult() : execResult.getError());
        } catch (Exception e) {
            log.error("Tool execution failed: tool={}", toolName, e);
            ctx.getFailCount().incrementAndGet();
            // Step onError
            for (ToolHook hook : toolHooks) {
                try {
                    return hook.onError(toolCall, e, ctx);
                } catch (Exception ignored) {
                    log.warn("ToolHook.onError failed", ignored);
                }
            }
            return "Error: " + e.getMessage();
        }

        // Step 6: afterExecution hooks
        for (ToolHook hook : toolHooks) {
            try {
                result = hook.afterExecution(result, toolCall, ctx);
            } catch (Exception e) {
                log.warn("ToolHook.afterExecution failed: {}", e.getMessage(), e);
            }
        }

        // Step 7: Format — 结果格式化（目前直接返回，后续可接 ResultFormatter）
        return result;
    }

    private Map<String, Object> parseArgs(String argumentsJson) {
        try {
            if (argumentsJson != null && !argumentsJson.isEmpty()) {
                return objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            log.warn("Failed to parse tool arguments JSON: {}", e.getMessage(), e);
        }
        return Map.of();
    }
}
