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

    /**
     * 构造存储后端后处理器，注入 StorageBackendRegistry 依赖。
     *
     * <p>StorageBackendRegistry 是所有存储后端的中央注册表，本处理器发现的每个
     * @StorageBackend 注解 Bean 最终都会注册到此注册表中。通过构造器注入确保注册表
     * 实例在处理器激活前已由 StorageAutoConfiguration 创建并完成初始化。</p>
     *
     * @param registry 存储后端注册表实例，由 StorageAutoConfiguration 创建并注入
     */
    public StorageBackendPostProcessor(StorageBackendRegistry registry) {
        this.registry = registry;
    }

    /**
     * 返回此 BeanPostProcessor 的执行顺序值，数值越小优先级越高。
     *
     * <p>返回 {@code Ordered.LOWEST_PRECEDENCE - 100}，确保在所有底层基础设施
     * （如 StorageBackendRegistry、StorageProperties）完全就绪后才执行。这个顺序
     * 保证了：内置后端（InMemoryBackend、FileBackend）已经由自动配置创建、
     * StorageBackendRegistry 已经可用且可以接收注册。同时为后续的 WritePolicyPostProcessor
     * （优先级 -90）和 MemorySystemAutoConfigurator（优先级 -50）预留了执行空间。</p>
     *
     * @return {@link Ordered#LOWEST_PRECEDENCE} - 100
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    /**
     * Spring Bean 后处理器核心方法，在 Bean 初始化完成后被容器调用，负责发现和注册存储后端。
     *
     * <p><b>处理流程：</b></p>
     * <ol>
     *   <li><b>注解检测：</b>通过 {@code getAnnotation(StorageBackend.class)} 检查当前 Bean
     *       是否标注了 {@code @StorageBackend} 注解，未标注则跳过。</li>
     *   <li><b>接口校验：</b>强制要求标注了 @StorageBackend 的 Bean 必须实现 StorageBackend
     *       接口，未实现则抛出 {@link LyClawException} 异常（STORAGE_ERROR）。</li>
     *   <li><b>层归属提取：</b>通过 {@link #extractLayers(Class)} 从 @SessionStore、
     *       @EntityStore、@MemoryStore 注解中提取后端所服务的存储层，存入 EnumSet 集合。</li>
     *   <li><b>能力声明提取：</b>通过 {@link #extractCapabilities(Class)} 从 @VectorStore、
     *       @GraphStore、@FullTextStore 注解中提取后端的能力声明，基础 KEY_VALUE 能力始终包含。</li>
     *   <li><b>能力交叉校验：</b>通过 {@link #validateCapabilityDeclaration(Set, Set, String)}
     *       对比注解声明能力和后端接口实际报告的能力，若有差异则记录警告日志（自动降级处理）。</li>
     *   <li><b>注册与初始化：</b>如果 {@code autoRegister} 为 true，将后端按名称注册到
     *       StorageBackendRegistry 中，附带层级归属和优先级信息。然后调用后端的
     *       {@code initialize()} 方法传入包含名称的初始配置 Map，完成后端自身的启动初始化。</li>
     * </ol>
     *
     * @param bean Spring 容器中已初始化的 Bean 实例，可能带有 @StorageBackend 注解
     * @param beanName Bean 在 Spring 容器中的注册名称，用于日志和异常信息
     * @return 始终返回原始 bean 实例
     * @throws BeansException 当 @StorageBackend 标注的 Bean 未实现 StorageBackend 接口时抛出
     */
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
