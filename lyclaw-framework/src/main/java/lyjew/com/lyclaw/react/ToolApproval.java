package lyjew.com.lyclaw.react;

/**
 * 待审批的工具调用，包含工具信息和用于等待用户响应的 Future。
 *
 * @param toolCallId  工具调用唯一标识
 * @param toolName    工具名称
 * @param arguments   JSON 格式的调用参数
 * @param message     前端展示的提示信息
 * @param future      用户响应 Future，true=允许 false=拒绝
 */
public record ToolApproval(
        String toolCallId,
        String toolName,
        String arguments,
        String message,
        java.util.concurrent.CompletableFuture<Boolean> future
) {}
