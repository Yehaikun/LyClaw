package lyjew.com.lyclaw.event.impl;

import lyjew.com.lyclaw.event.Event;
import lyjew.com.lyclaw.event.EventBus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * InMemoryEventBus —— 内存事件总线实现。
 *
 * <p>使用 ConcurrentHashMap 按事件类型（Class）存储订阅者列表，
 * 每个订阅者列表使用 CopyOnWriteArrayList 保证并发安全。
 * publish() 时遍历所有匹配类型的订阅者执行。</p>
 *
 * <p><b>设计动机</b>：事件总线是解耦组件间通信的关键机制。
 * 如果不使用事件总线，组件之间需要直接依赖对方的接口进行通信，
 * 导致代码耦合度高。通过事件总线：
 * <ul>
 *   <li>MetricsStage 发布事件 → LoggingInterceptor 消费，两者互不依赖</li>
 *   <li>新增监控模块只需订阅事件，不需修改现有代码</li>
 * </ul>
 * </p>
 *
 * <p><b>线程安全</b>：
 * <ul>
 *   <li>subscribe/unsubscribe：CopyOnWriteArrayList 写时复制，遍历线程安全</li>
 *   <li>publish：遍历快照执行，不存在 ConcurrentModificationException</li>
 *   <li>类型映射：ConcurrentHashMap，并发无锁</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see EventBus
 */
@Component
public class InMemoryEventBus implements EventBus {

    /**
     * 事件类型 → 订阅者列表映射。
     *
     * <p>每个事件类型（Class）对应一组订阅者（Consumer）。
     * publish(Event) 时根据 Event.getClass() 查找匹配的订阅者执行。</p>
     */
    private final ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<Consumer<?>>> subscribers = new ConcurrentHashMap<>();

    /**
     * 发布事件。遍历所有订阅了该事件类型的消费者执行。
     *
     * @param event 要发布的事件
     */
    @SuppressWarnings("unchecked")
    @Override
    public void publish(Event event) {
        // 查找该事件类型对应的订阅者列表
        CopyOnWriteArrayList<Consumer<?>> list = subscribers.get(event.getClass());

        if (list != null) {
            // 遍历所有订阅者执行 —— CopyOnWriteArrayList 保证遍历安全
            for (Consumer<?> consumer : list) {
                ((Consumer<Event>) consumer).accept(event);
            }
        }
    }

    /**
     * 订阅事件。当指定类型的事件发布时，consumer 被执行。
     *
     * @param eventType 要订阅的事件类型
     * @param consumer  事件消费者
     * @param <T>       事件类型泛型
     */
    @Override
    public <T extends Event> void subscribe(Class<T> eventType, Consumer<T> consumer) {
        // computeIfAbsent：没有订阅者列表时创建新的 CopyOnWriteArrayList
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(consumer);
    }

    /**
     * 取消订阅。从指定事件类型的订阅者列表中移除。
     *
     * @param eventType 要取消订阅的事件类型
     * @param consumer  要移除的消费者
     * @param <T>       事件类型泛型
     */
    @Override
    public <T extends Event> void unsubscribe(Class<T> eventType, Consumer<T> consumer) {
        CopyOnWriteArrayList<Consumer<?>> list = subscribers.get(eventType);
        if (list != null) {
            list.remove(consumer);
            // 如果列表为空，清理条目
            if (list.isEmpty()) {
                subscribers.remove(eventType);
            }
        }
    }

    /**
     * 清空所有订阅者。
     */
    @Override
    public void clear() {
        subscribers.clear();
    }
}