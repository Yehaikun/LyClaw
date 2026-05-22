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
        long pipelineStart = System.currentTimeMillis();
        log.info("⚙️ [ToolPipeline] 开始执行 7步管线 | toolName={} toolCallId={}", toolName, toolCallId);

        // Step 1: Resolve — 查找工具实例
        long t1 = System.currentTimeMillis();
        Tool tool = toolRegistry.get(toolName);
        if (tool == null) {
            log.warn("❌ [ToolPipeline] Step1-Resolve失败: 工具未找到 toolName={}", toolName);
            return "Error: tool not found: " + toolName;
        }
        log.info("  ├─ [Step1-Resolve] 工具查找完成 | 耗时={}ms | type={}",
                System.currentTimeMillis() - t1, tool.getClass().getSimpleName());

        // Step 2: Policy check — 策略检查（频率限制、黑白名单）
        long t2 = System.currentTimeMillis();
        if (toolCallPolicy != null && !toolCallPolicy.canExecute(toolName, null)) {
            log.warn("⛔ [ToolPipeline] Step2-Policy拦截: toolName={}", toolName);
            return "Error: tool call blocked by policy: " + toolName;
        }
        log.info("  ├─ [Step2-Policy] 策略检查通过 | 耗时={}ms | policy={}",
                System.currentTimeMillis() - t2,
                toolCallPolicy != null ? toolCallPolicy.getClass().getSimpleName() : "none");

        // Step 3: beforeExecution hooks
        long t3 = System.currentTimeMillis();
        if (toolHooks.isEmpty()) {
            log.info("  ├─ [Step3-beforeHook] 无注册钩子，跳过");
        } else {
            log.info("  ├─ [Step3-beforeHook] 执行 {} 个钩子: {}",
                    toolHooks.size(),
                    toolHooks.stream().map(h -> h.getClass().getSimpleName() + "(order=" + h.getOrder() + ")").toList());
            for (ToolHook hook : toolHooks) {
                try {
                    log.info("    ├─ {}(order={})", hook.getClass().getSimpleName(), hook.getOrder());
                    hook.beforeExecution(toolCall, ctx);
                } catch (Exception e) {
                    log.warn("    └─ ToolHook.beforeExecution失败: {} error={}", hook.getClass().getSimpleName(), e.getMessage(), e);
                    return hook.onError(toolCall, e, ctx);
                }
            }
        }
        log.info("  ├─ [Step3-beforeHook] 完成 | 耗时={}ms", System.currentTimeMillis() - t3);

        // Step 4: Bind — 参数绑定
        long t4 = System.currentTimeMillis();
        Map<String, Object> args = parseArgs(toolCall.getArguments());
        log.info("  ├─ [Step4-Bind] 参数绑定完成 | 耗时={}ms | 参数数={}",
                System.currentTimeMillis() - t4, args.size());

        // Step 5: Invoke — 实际执行
        long t5 = System.currentTimeMillis();
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
            log.info("  ├─ [Step5-Invoke] 执行完成 | 耗时={}ms | 成功={} | resultLen={}",
                    System.currentTimeMillis() - t5, execResult.isSuccess(),
                    result != null ? result.length() : 0);
        } catch (Exception e) {
            log.error("  ├─ [Step5-Invoke] 执行异常 | 耗时={}ms | tool={} error={}",
                    System.currentTimeMillis() - t5, toolName, e.getMessage(), e);
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
        long t6 = System.currentTimeMillis();
        if (toolHooks.isEmpty()) {
            log.info("  ├─ [Step6-afterHook] 无注册钩子，跳过");
        } else {
            for (ToolHook hook : toolHooks) {
                try {
                    log.info("    ├─ {}(order={})", hook.getClass().getSimpleName(), hook.getOrder());
                    result = hook.afterExecution(result, toolCall, ctx);
                } catch (Exception e) {
                    log.warn("    └─ ToolHook.afterExecution失败: {} error={}", hook.getClass().getSimpleName(), e.getMessage(), e);
                }
            }
        }
        log.info("  ├─ [Step6-afterHook] 完成 | 耗时={}ms | resultLen={}",
                System.currentTimeMillis() - t6, result != null ? result.length() : 0);

        // Step 7: Format — 结果格式化（目前直接返回，后续可接 ResultFormatter）
        long totalDuration = System.currentTimeMillis() - pipelineStart;
        log.info("  └─ [Step7-Format] 管线全部完成 | 总耗时={}ms | toolName={}", totalDuration, toolName);
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
