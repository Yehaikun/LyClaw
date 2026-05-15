package lyjew.com.lyclaw.react;

/**
 * 工具执行器函数式接口，作为 ReAct 引擎与外部工具服务的桥接。
 *
 * <p>ReAct 引擎本身不依赖任何具体的工具执行机制（如 Feign 远程调用、
 * 本地反射调用），而是通过此接口将工具执行委托给调用方。
 * 调用方（如 RespondStage）通过闭包捕获 ActionFeignClient 来实现远程工具执行。
 */
@FunctionalInterface
public interface ToolExecutor {

    /**
     * 执行工具调用并返回结果字符串。
     *
     * @param toolName      工具名称
     * @param toolCallId    工具调用 ID（用于关联 assistant 和 tool 消息）
     * @param argumentsJson JSON 格式的调用参数字符串
     * @return 工具执行结果文本，失败时返回以 "Error:" 开头的错误描述
     */
    String execute(String toolName, String toolCallId, String argumentsJson);
}
