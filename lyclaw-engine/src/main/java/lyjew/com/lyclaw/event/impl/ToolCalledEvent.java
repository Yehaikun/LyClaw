package lyjew.com.lyclaw.event.impl;

import lyjew.com.lyclaw.event.Event;
import lyjew.com.lyclaw.tool.ToolResult;

/**
 * 工具调用事件 —— 当工具被执行时发布。
 *
 * <p>ToolCallLoop 在执行完每个工具后发布此事件，
 * 供日志和监控模块记录工具调用情况。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Event
 */
public class ToolCalledEvent extends Event {

    /** 工具名称 */
    private final String toolName;

    /** 工具调用参数（JSON 字符串） */
    private final String arguments;

    /** 工具执行结果 */
    private final ToolResult result;

    /** 执行耗时（毫秒） */
    private final long elapsedMs;

    /**
     * 构造工具调用事件。
     *
     * @param source    事件来源
     * @param toolName  工具名称
     * @param arguments 工具参数
     * @param result    执行结果
     * @param elapsedMs 执行耗时（ms）
     */
    public ToolCalledEvent(String source, String toolName, String arguments,
                           ToolResult result, long elapsedMs) {
        super(source, "TOOL_CALLED");
        this.toolName = toolName;
        this.arguments = arguments;
        this.result = result;
        this.elapsedMs = elapsedMs;
    }

    /** @return 工具名称 */
    public String getToolName() { return toolName; }

    /** @return 工具参数 */
    public String getArguments() { return arguments; }

    /** @return 执行结果 */
    public ToolResult getResult() { return result; }

    /** @return 执行耗时（ms） */
    public long getElapsedMs() { return elapsedMs; }
}