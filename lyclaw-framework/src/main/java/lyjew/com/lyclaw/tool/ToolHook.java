package lyjew.com.lyclaw.tool;

import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.react.AgentContext;

/**
 * 工具执行钩子 SPI，在工具执行的各个步骤提供拦截点。
 */
public interface ToolHook {

    /** 工具执行前回调。抛出异常可中断执行。 */
    default void beforeExecution(ToolCall toolCall, AgentContext ctx) {}

    /** 工具执行后回调，可修改返回结果。 */
    default String afterExecution(String result, ToolCall toolCall, AgentContext ctx) {
        return result;
    }

    /** 工具执行异常回调，可返回降级结果。 */
    default String onError(ToolCall toolCall, Throwable error, AgentContext ctx) {
        return "Error: " + error.getMessage();
    }

    /** 优先级，数值越小越先执行。默认 100。 */
    default int getOrder() { return 100; }
}
