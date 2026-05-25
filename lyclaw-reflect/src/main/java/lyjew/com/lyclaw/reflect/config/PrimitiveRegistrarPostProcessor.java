package lyjew.com.lyclaw.reflect.config;

import lyjew.com.lyclaw.annotation.reflect.Primitive;
import lyjew.com.lyclaw.reflect.primitive.ReflectionPrimitive;
import lyjew.com.lyclaw.reflect.registry.PrimitiveFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * 扫描所有标注了 {@link Primitive} 的 Bean，在初始化后自动注册到 {@link PrimitiveFactory}。
 *
 * <p>替代原先 {@code ReflectAutoConfiguration.primitiveRegistrar()} 的手动注册，
 * 使新增原语实现时无需修改配置类。
 */
public class PrimitiveRegistrarPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(PrimitiveRegistrarPostProcessor.class);

    private final PrimitiveFactory factory;

    public PrimitiveRegistrarPostProcessor(PrimitiveFactory factory) {
        this.factory = factory;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> clazz = bean.getClass();
        Primitive annotation = clazz.getAnnotation(Primitive.class);
        if (annotation == null) return bean;

        if (!(bean instanceof ReflectionPrimitive)) {
            log.warn("@Primitive annotated class {} does not implement ReflectionPrimitive", clazz.getName());
            return bean;
        }

        factory.register(annotation.type(), annotation.name(), (ReflectionPrimitive) bean);
        log.debug("Auto-registered primitive {}:{} -> {}", annotation.type(), annotation.name(), clazz.getSimpleName());

        if (annotation.isDefault()) {
            factory.registerDefault(annotation.type(), annotation.name());
            log.info("Auto-registered default primitive {}:{} -> {}", annotation.type(), annotation.name(), clazz.getSimpleName());
        }

        return bean;
    }
}
