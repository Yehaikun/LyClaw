package lyjew.com.lyclaw.event;

import java.util.function.Consumer;

/**
 * 事件总线接口 —— 解耦事件的发布者和订阅者。
 *
 * <p>EventBus 使用发布-订阅模式：发布者不需要知道谁在监听事件，
 * 订阅者不需要知道谁发布了事件。这种松耦合使得新增事件监听逻辑时，
 * 不需要修改发布者的任何代码。</p>
 *
 * <p><b>设计动机</b>：在不使用 EventBus 的情况下，
 * 每次 Token 消耗后需要手动调用日志记录、指标采集等多个组件，
 * 代码耦合度高。通过 EventBus，发布者只管发布事件，
 * 各组件通过 subscribe 独立监听。</p>
 *
 * <p><b>已知实现</b>：
 * <ul>
 *   <li>InMemoryEventBus — 内存实现，适用于单节点部署</li>
 *   <li>NullEventBus — 空对象实现，禁用了事件功能</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Event
 */
public interface EventBus {

    /**
     * 发布事件。所有匹配的订阅者会同步收到事件通知。
     * 如果某个订阅者抛出异常，不会影响其他订阅者的接收。
     *
     * @param event 要发布的事件，不可为 null
     */
    void publish(Event event);

    /**
     * 订阅指定类型的事件。
     *
     * @param <T>       事件类型
     * @param eventType 要订阅的事件 Class
     * @param handler   事件处理回调
     */
    <T extends Event> void subscribe(Class<T> eventType, Consumer<T> handler);

    /**
     * 取消订阅指定类型的事件。
     *
     * @param <T>       事件类型
     * @param eventType 要取消订阅的事件 Class
     * @param handler   之前注册的处理回调
     */
    <T extends Event> void unsubscribe(Class<T> eventType, Consumer<T> handler);

    /**
     * 清除所有订阅者。
     */
    void clear();
}