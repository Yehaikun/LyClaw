package lyjew.com.lyclaw.annotation.reflect;

import lyjew.com.lyclaw.reflect.topology.PrimitiveType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个类为反射原语实现，供 {@code PrimitiveRegistrarPostProcessor}
 * 在 Bean 初始化后自动注册到 {@link lyjew.com.lyclaw.reflect.registry.PrimitiveFactory}。
 *
 * <p>使用此注解的类必须实现 {@link lyjew.com.lyclaw.reflect.primitive.ReflectionPrimitive}
 * 对应的子接口（Actor、Evaluator、Router 等），否则启动时会输出警告日志。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Primitive {

    /** 原语类型 */
    PrimitiveType type();

    /** 注册名称（在 PrimitiveFactory 中唯一标识该实现） */
    String name();

    /** 是否设为该类型的默认实现 */
    boolean isDefault() default false;
}
