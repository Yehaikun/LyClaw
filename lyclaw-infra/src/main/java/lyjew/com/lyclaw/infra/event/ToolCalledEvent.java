package lyjew.com.lyclaw.infra.event;

import lyjew.com.lyclaw.event.Event;
import java.util.Map;

/**
 * 工具调用事件，在工具执行完成后发布。
 *
 * <p>携带工具名称、调用参数、执行结果（成功/失败）、延迟时长和会话标识等信息，
 * 供监控、告警、审计等下游模块消费。</p>
 */
public class ToolCalledEvent extends Event {

    /** 被调用的工具名称 */
    private final String toolName;
    /** 工具调用参数 */
    private final Map<String, Object> args;
    /** 调用是否成功 */
    private final boolean success;
    /** 调用耗时（毫秒） */
    private final long latencyMs;
    /** 关联的会话 ID */
    private final String sessionId;

    /**
     * 构造一个工具调用事件。
     *
     * @param source    事件来源标识
     * @param toolName  工具名称
     * @param args      调用参数
     * @param success   是否执行成功
     * @param latencyMs 执行延迟（毫秒）
     * @param sessionId 会话 ID
     */
    public ToolCalledEvent(String source, String toolName, Map<String, Object> args,
                           boolean success, long latencyMs, String sessionId) {
        super(source, "TOOL_CALLED");
        this.toolName = toolName;
        this.args = args;
        this.success = success;
        this.latencyMs = latencyMs;
        this.sessionId = sessionId;
    }

    /** @return 工具名称 */
    public String getToolName() { return toolName; }
    /** @return 调用参数 */
    public Map<String, Object> getArgs() { return args; }
    /** @return 是否成功 */
    public boolean isSuccess() { return success; }
    /** @return 执行延迟（毫秒） */
    public long getLatencyMs() { return latencyMs; }
    /** @return 会话 ID */
    public String getSessionId() { return sessionId; }
}
