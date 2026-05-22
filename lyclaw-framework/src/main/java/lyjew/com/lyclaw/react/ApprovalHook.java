package lyjew.com.lyclaw.react;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lyjew.com.lyclaw.model.ToolCall;

/**
 * 工具审批 Hook，对需要审批的工具在执行前等待用户确认。
 *
 * <p>order=30，在沙箱包装之后、实际执行之前。
 * 仅对 approvalTools 集合中列出的工具生效，其他工具直接放行。
 * 同时实现 wrapToolCall（步级）和 wrapToolExecutor（请求级）两个粒度。
 * 30 秒超时自动拒绝。
 */
public class ApprovalHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(ApprovalHook.class);
    private static final long APPROVAL_TIMEOUT_SECONDS = 30;

    private final ApprovalStore approvalStore;
    private final Set<String> approvalTools;

    public ApprovalHook(ApprovalStore approvalStore, Set<String> approvalTools) {
        this.approvalStore = approvalStore;
        this.approvalTools = approvalTools != null ? Set.copyOf(approvalTools) : Set.of();
    }

    @Override
    public int getOrder() { return 30; }

    /**
     * 步级拦截：每次工具调用前检查是否需要审批。
     * 对审批工具打标，实际审批等待在 wrapToolExecutor 中完成。
     */
    @Override
    public ToolCall wrapToolCall(ToolCall toolCall, AgentContext ctx) {
        if (approvalStore == null || approvalTools.isEmpty()) {
            return toolCall;
        }
        if (approvalTools.contains(toolCall.getName())) {
            log.info("工具需审批: tool={} toolCallId={}", toolCall.getName(), toolCall.getToolCallId());
            ctx.setAttribute("approval_pending_" + toolCall.getToolCallId(), Boolean.TRUE);
        }
        return toolCall;
    }

    @Override
    public ToolExecutor wrapToolExecutor(ToolExecutor inner, AgentContext ctx) {
        if (approvalStore == null || approvalTools.isEmpty()) {
            return inner;
        }
        return (toolName, toolCallId, argumentsJson) -> {
            if (!approvalTools.contains(toolName)) {
                return inner.execute(toolName, toolCallId, argumentsJson);
            }
            log.info("等待审批: tool={} toolCallId={}", toolName, toolCallId);
            CompletableFuture<Boolean> future = approvalStore.create(
                    toolCallId, ctx.getSessionId(), ctx.getAgentId(), toolName, argumentsJson);
            try {
                Boolean approved = future.get(APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (Boolean.TRUE.equals(approved)) {
                    log.info("工具审批通过: tool={} toolCallId={}", toolName, toolCallId);
                    return inner.execute(toolName, toolCallId, argumentsJson);
                }
                log.info("工具审批拒绝: tool={} toolCallId={}", toolName, toolCallId);
                return "Tool execution denied by user: " + toolName;
            } catch (java.util.concurrent.TimeoutException e) {
                log.warn("工具审批超时: tool={} toolCallId={}", toolName, toolCallId);
                approvalStore.deny(toolCallId);
                return "Tool execution timed out waiting for approval: " + toolName;
            } catch (Exception e) {
                log.error("工具审批异常: tool={} toolCallId={}", toolName, toolCallId, e);
                return "Error: " + e.getMessage();
            }
        };
    }
}
