package lyjew.com.lyclaw.event.impl;

import lyjew.com.lyclaw.event.Event;
import lyjew.com.lyclaw.event.EventBus;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
/**
 * EventBus 空对象实现 —— 所有方法空操作，不产生任何副作用。
 *
 * <p>当应用不需要 EventBus 功能时，注入此实现避免 NPE。
 * publish/subscribe/unsubscribe/clear 全部空操作。</p>
 *
 * <p><b>Spring 注入</b>：@Component + @ConditionalOnMissingBean(EventBus.class)，
 * 当没有其他 EventBus 实现时自动使用此空对象。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
@Component
@ConditionalOnMissingBean(EventBus.class)
public class NullEventBus implements EventBus {

    @Override
    public void publish(Event event) { /* 空操作 */ }

    @Override
    public <T extends Event> void subscribe(Class<T> eventType, Consumer<T> handler) {
        /* 空操作 */
    }

    @Override
    public <T extends Event> void unsubscribe(Class<T> eventType, Consumer<T> handler) {
        /* 空操作 */
    }

    @Override
    public void clear() { /* 空操作 */ }
}