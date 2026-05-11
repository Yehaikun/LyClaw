package lyjew.com.lyclaw.event;

import java.util.function.Consumer;

public interface EventBus {

    void publish(Event event);

    <T extends Event> void subscribe(Class<T> eventType, Consumer<T> handler);

    <T extends Event> void unsubscribe(Class<T> eventType, Consumer<T> handler);

    void clear();
}
