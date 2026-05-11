package lyjew.com.lyclaw.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 事件基类，框架内所有事件的父类。
 *
 * <p>每个事件具有唯一的 eventId（UUID 生成）、自动填充的时间戳、
 * 事件来源（source）和事件类型（eventType）。子类可以通过继承此类
 * 来携带特定领域的业务数据。</p>
 *
 * <p>通过 {@link EventBus} 发布和订阅，实现模块间的松耦合通信。</p>
 */
public class Event {

    /** 事件的唯一标识符（UUID） */
    private final String eventId;
    /** 事件发生的时间戳 */
    private final Instant timestamp;
    /** 事件来源，标识哪个组件产生了该事件 */
    private final String source;
    /** 事件类型，用于订阅者过滤关注的事件 */
    private final String eventType;

    /**
     * 构造一个新事件，自动生成事件 ID 和时间戳。
     *
     * @param source    事件来源组件名称
     * @param eventType 事件类型标签
     */
    public Event(String source, String eventType) {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
        this.source = source;
        this.eventType = eventType;
    }

    /** @return 事件唯一 ID */
    public String getEventId() { return eventId; }

    /** @return 事件时间戳 */
    public Instant getTimestamp() { return timestamp; }

    /** @return 事件来源 */
    public String getSource() { return source; }

    /** @return 事件类型 */
    public String getEventType() { return eventType; }
}
