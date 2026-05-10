package lyjew.com.lyclaw.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 事件基类 —— 所有事件的基类。
 *
 * <p>EventBus 中发布的所有事件都继承此类。
 * 每个事件有唯一 ID、时间戳、来源和类型标识。</p>
 *
 * <p><b>设计动机</b>：如果事件没有统一基类，EventBus 的 subscribe 方法
 * 就无法做类型安全的事件过滤。通过泛型 {@link EventBus#subscribe(Class, java.util.function.Consumer)}，
 * 订阅者可以只接收自己关心的事件类型。</p>
 *
 * <p><b>已知的子类</b>：
 * <ul>
 *   <li>TokenConsumedEvent — Token 消耗事件</li>
 *   <li>ToolCalledEvent — 工具调用事件</li>
 *   <li>AgentStateChangedEvent — Agent 状态变更事件</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see EventBus
 */
public class Event {

    /** 事件唯一 ID。使用 UUID 确保全局唯一 */
    private final String eventId;

    /** 事件创建时间。使用 Instant 确保时间精度到纳秒 */
    private final Instant timestamp;

    /** 事件来源标识。通常使用发布该事件的类名，方便追踪 */
    private final String source;

    /** 事件类型标识。如 "TOKEN_CONSUMED"、"TOOL_CALLED"、"AGENT_STATE_CHANGED" */
    private final String eventType;

    /**
     * 构造一个事件实例。
     *
     * @param source    事件来源标识
     * @param eventType 事件类型标识
     */
    public Event(String source, String eventType) {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
        this.source = source;
        this.eventType = eventType;
    }

    /** @return 事件唯一 ID */
    public String getEventId() { return eventId; }

    /** @return 事件创建时间 */
    public Instant getTimestamp() { return timestamp; }

    /** @return 事件来源标识 */
    public String getSource() { return source; }

    /** @return 事件类型标识 */
    public String getEventType() { return eventType; }
}