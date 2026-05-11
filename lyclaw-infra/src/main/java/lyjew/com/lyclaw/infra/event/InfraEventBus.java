package lyjew.com.lyclaw.infra.event;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.event.Event;
import lyjew.com.lyclaw.event.EventBus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 基础设施层事件总线实现，提供同步/异步事件发布和订阅管理功能。
 *
 * <p>支持两种订阅模式：
 * <ul>
 *   <li><b>精确匹配</b>：只投递给与事件类型完全匹配的订阅者</li>
 *   <li><b>通配匹配</b>：投递给所有父类型匹配的订阅者（isAssignableFrom）</li>
 * </ul>
 * 异步发布使用虚拟线程执行，不阻塞调用方。</p>
 */
@Slf4j
@Component("infraEventBus")
public class InfraEventBus implements EventBus {

    /** 精确类型订阅者映射：事件类型 -> 订阅者列表 */
    private final Map<Class<?>, CopyOnWriteArrayList<Consumer<?>>> subscribers = new ConcurrentHashMap<>();
    /** 通配订阅者映射：父类型 -> 订阅者列表（使用 isAssignableFrom 匹配） */
    private final Map<Class<?>, List<Consumer<?>>> wildcardSubscribers = new ConcurrentHashMap<>();
    /** 异步事件执行的虚拟线程池 */
    private final ExecutorService asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 同步发布事件。
     *
     * <p>先通知精确匹配的订阅者，再通知通配匹配的订阅者。
     * 单个订阅者的异常不会影响其他订阅者。</p>
     *
     * @param event 要发布的事件
     */
    @Override
    @SuppressWarnings("unchecked")
    public void publish(Event event) {
        log.debug("[EventBus] publish {}", event.getClass().getSimpleName());

        // 通知精确匹配的订阅者
        CopyOnWriteArrayList<Consumer<?>> exactList = subscribers.get(event.getClass());
        if (exactList != null) {
            for (Consumer<?> c : exactList) {
                try {
                    ((Consumer<Event>) c).accept(event);
                } catch (Exception e) {
                    log.error("[EventBus] subscriber error for {}", event.getClass().getSimpleName(), e);
                }
            }
        }

        // 通知通配（父类型）匹配的订阅者
        for (Map.Entry<Class<?>, List<Consumer<?>>> entry : wildcardSubscribers.entrySet()) {
            if (entry.getKey().isAssignableFrom(event.getClass())) {
                for (Consumer<?> c : entry.getValue()) {
                    try {
                        ((Consumer<Event>) c).accept(event);
                    } catch (Exception e) {
                        log.error("[EventBus] wildcard subscriber error", e);
                    }
                }
            }
        }
    }

    /**
     * 异步发布事件，提交到虚拟线程池执行，不阻塞调用方。
     *
     * @param event 要发布的事件
     */
    public void publishAsync(Event event) {
        asyncExecutor.submit(() -> publish(event));
    }

    /**
     * 订阅指定类型的事件（精确匹配）。
     *
     * @param eventType 事件类型
     * @param consumer  事件消费者
     * @param <T>       事件子类型
     */
    @Override
    public <T extends Event> void subscribe(Class<T> eventType, Consumer<T> consumer) {
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(consumer);
    }

    /**
     * 取消订阅。
     *
     * @param eventType 事件类型
     * @param consumer  要移除的消费者
     * @param <T>       事件子类型
     */
    @Override
    public <T extends Event> void unsubscribe(Class<T> eventType, Consumer<T> consumer) {
        CopyOnWriteArrayList<Consumer<?>> list = subscribers.get(eventType);
        if (list != null) {
            list.remove(consumer);
            // 如果该类型的订阅者列表为空，清理映射
            if (list.isEmpty()) subscribers.remove(eventType);
        }
    }

    /** 清空所有订阅者 */
    @Override
    public void clear() {
        subscribers.clear();
        wildcardSubscribers.clear();
    }
}
