package lyjew.com.lyclaw.tool;

import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.react.AgentContext;

/**
 * 工具执行管线接口，统一 7 步工具执行流程。
 */
public interface ToolExecutionPipeline {

    /**
     * 执行完整的 7 步管线：resolve → policy → beforeHook → bind → invoke → afterHook → format。
     *
     * @param toolCall 工具调用请求
     * @param ctx      Agent 上下文
     * @return 格式化后的工具执行结果字符串
     */
    String execute(ToolCall toolCall, AgentContext ctx);
}
