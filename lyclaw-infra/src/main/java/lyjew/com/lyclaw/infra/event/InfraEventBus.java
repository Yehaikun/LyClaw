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

@Slf4j
@Component("infraEventBus")
public class InfraEventBus implements EventBus {

    private final Map<Class<?>, CopyOnWriteArrayList<Consumer<?>>> subscribers = new ConcurrentHashMap<>();
    private final Map<Class<?>, List<Consumer<?>>> wildcardSubscribers = new ConcurrentHashMap<>();
    private final ExecutorService asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    @SuppressWarnings("unchecked")
    public void publish(Event event) {
        log.debug("[EventBus] publish {}", event.getClass().getSimpleName());

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

    public void publishAsync(Event event) {
        asyncExecutor.submit(() -> publish(event));
    }

    @Override
    public <T extends Event> void subscribe(Class<T> eventType, Consumer<T> consumer) {
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(consumer);
    }

    @Override
    public <T extends Event> void unsubscribe(Class<T> eventType, Consumer<T> consumer) {
        CopyOnWriteArrayList<Consumer<?>> list = subscribers.get(eventType);
        if (list != null) {
            list.remove(consumer);
            if (list.isEmpty()) subscribers.remove(eventType);
        }
    }

    @Override
    public void clear() {
        subscribers.clear();
        wildcardSubscribers.clear();
    }
}
