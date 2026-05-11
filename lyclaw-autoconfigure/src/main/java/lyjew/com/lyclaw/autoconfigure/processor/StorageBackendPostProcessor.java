package lyjew.com.lyclaw.autoconfigure.processor;

import lyjew.com.lyclaw.annotation.storage.EntityStore;
import lyjew.com.lyclaw.annotation.storage.FullTextStore;
import lyjew.com.lyclaw.annotation.storage.GraphStore;
import lyjew.com.lyclaw.annotation.storage.MemoryStore;
import lyjew.com.lyclaw.annotation.storage.SessionStore;
import lyjew.com.lyclaw.annotation.storage.VectorStore;
import lyjew.com.lyclaw.base.exception.LyClawException;
import lyjew.com.lyclaw.enums.ErrorCode;
import lyjew.com.lyclaw.storage.StorageBackend;
import lyjew.com.lyclaw.storage.StorageBackendRegistry;
import lyjew.com.lyclaw.storage.StorageCapability;
import lyjew.com.lyclaw.storage.StoreLayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

import java.util.*;

/**
 * 存储后端注册处理器，扫描 {@code @StorageBackend} 注解的 Bean 并自动注册到 StorageBackendRegistry。
 *
 * <p>执行顺序：LOWEST_PRECEDENCE - 100，确保所有基础组件已就绪。
 * 处理流程：提取注解信息→校验接口实现→交叉校验能力声明→注册→输出启动摘要日志。</p>
 */
public class StorageBackendPostProcessor implements BeanPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(StorageBackendPostProcessor.class);

    private final StorageBackendRegistry registry;

    public StorageBackendPostProcessor(StorageBackendRegistry registry) {
        this.registry = registry;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        lyjew.com.lyclaw.annotation.storage.StorageBackend annotation =
                bean.getClass().getAnnotation(lyjew.com.lyclaw.annotation.storage.StorageBackend.class);
        if (annotation == null) return bean;

        if (!(bean instanceof StorageBackend backend)) {
            throw new LyClawException(
                    ErrorCode.STORAGE_ERROR.code(), ErrorCode.STORAGE_ERROR.httpStatus(),
                    "类 " + bean.getClass().getName() + " 标注了 @StorageBackend 但未实现 StorageBackend 接口");
        }

        // 提取层归属
        Set<StoreLayer> layers = extractLayers(bean.getClass());
        // 提取能力声明
        Set<StorageCapability> declaredCapabilities = extractCapabilities(bean.getClass());

        // 交叉校验：注解声明能力 vs 接口实际实现能力
        Set<StorageCapability> actualCapabilities = backend.capabilities();
        validateCapabilityDeclaration(declaredCapabilities, actualCapabilities, beanName);

        // 注册
        String name = annotation.name();
        if (name.isEmpty()) {
            name = Character.toLowerCase(bean.getClass().getSimpleName().charAt(0))
                    + bean.getClass().getSimpleName().substring(1);
        }
        if (annotation.autoRegister()) {
            registry.register(name, backend, layers, annotation.priority());
        }

        // 初始化
        Map<String, Object> initConfig = new HashMap<>();
        initConfig.put("name", name);
        backend.initialize(initConfig);

        log.info("注册存储后端: {} (层级: {}, 能力: {}, 优先级: {})",
                name, layers, actualCapabilities, annotation.priority());

        return bean;
    }

    private Set<StoreLayer> extractLayers(Class<?> clazz) {
        Set<StoreLayer> layers = EnumSet.noneOf(StoreLayer.class);
        if (clazz.isAnnotationPresent(SessionStore.class)) layers.add(StoreLayer.SESSION);
        if (clazz.isAnnotationPresent(EntityStore.class)) layers.add(StoreLayer.ENTITY);
        if (clazz.isAnnotationPresent(MemoryStore.class)) layers.add(StoreLayer.MEMORY);
        return layers;
    }

    private Set<StorageCapability> extractCapabilities(Class<?> clazz) {
        Set<StorageCapability> caps = EnumSet.of(StorageCapability.KEY_VALUE);
        if (clazz.isAnnotationPresent(VectorStore.class)) caps.add(StorageCapability.VECTOR_SEARCH);
        if (clazz.isAnnotationPresent(GraphStore.class)) caps.add(StorageCapability.GRAPH);
        if (clazz.isAnnotationPresent(FullTextStore.class)) caps.add(StorageCapability.FULL_TEXT);
        return caps;
    }

    private void validateCapabilityDeclaration(Set<StorageCapability> declared,
                                                Set<StorageCapability> actual,
                                                String beanName) {
        for (StorageCapability cap : declared) {
            if (!actual.contains(cap)) {
                log.warn("存储后端 {} 声明的能力 {} 但接口未实现，已自动降级", beanName, cap);
            }
        }
    }
}
