package lyjew.com.lyclaw.annotation.storage;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import lyjew.com.lyclaw.storage.MemoryLayer;

/**
 * 声明该类是一个记忆写策略实现。
 *
 * <p>标注了此注解的类自动注册到 MemoryWriteManager，
 * 供 TieredMemorySystem 在需要 flush 时调用。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface WritePolicy {

    /** 策略名称 */
    String name();

    /** 策略描述 */
    String description() default "";

    /** 适用的记忆层级 */
    MemoryLayer[] applicableLayers() default { MemoryLayer.SHORT_TERM, MemoryLayer.LONG_TERM };

    /** 是否默认策略 */
    boolean defaultPolicy() default false;
}
