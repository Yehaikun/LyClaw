package lyjew.com.lyclaw.event;

import java.util.function.Consumer;

/**
 * 事件总线接口，定义事件的发布与订阅机制。
 *
 * <p>EventBus 是框架内各模块之间松耦合通信的核心组件。发布者调用
 * {@link #publish(Event)} 发送事件，订阅者通过 {@link #subscribe(Class, Consumer)}
 * 按事件类型注册处理器。每个订阅基于事件的具体 Class 类型进行精确匹配，
 * 不支持继承层次匹配。</p>
 *
 * <p>使用方式示例：</p>
 * <pre>{@code
 *   eventBus.subscribe(MyEvent.class, event -> handleMyEvent(event));
 *   eventBus.publish(new MyEvent("source", "type"));
 *   eventBus.unsubscribe(MyEvent.class, handler);
 * }</pre>
 */
public interface EventBus {

    /**
     * 发布一个事件，通知所有订阅了该事件类型的处理器。
     *
     * @param event 要发布的事件对象
     */
    void publish(Event event);

    /**
     * 订阅指定类型的事件。
     *
     * @param <T>       事件的具体类型，必须继承 Event
     * @param eventType 要订阅的事件 Class 对象
     * @param handler   收到事件时的回调处理器
     */
    <T extends Event> void subscribe(Class<T> eventType, Consumer<T> handler);

    /**
     * 取消订阅指定类型的事件。
     *
     * @param <T>       事件的具体类型
     * @param eventType 要取消订阅的事件 Class 对象
     * @param handler   之前注册的处理器（必须为同一个引用）
     */
    <T extends Event> void unsubscribe(Class<T> eventType, Consumer<T> handler);

    /**
     * 清空所有订阅，移除所有注册的处理器。
     * 通常用于测试清理或系统重置场景。
     */
    void clear();
}
