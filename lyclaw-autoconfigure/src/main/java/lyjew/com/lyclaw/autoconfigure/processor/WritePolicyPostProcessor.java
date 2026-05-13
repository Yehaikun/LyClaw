package lyjew.com.lyclaw.autoconfigure.processor;

import lyjew.com.lyclaw.annotation.storage.WritePolicy;
import lyjew.com.lyclaw.base.exception.LyClawException;
import lyjew.com.lyclaw.enums.ErrorCode;
import lyjew.com.lyclaw.storage.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

import java.util.*;

/**
 * 写策略注册处理器，扫描 {@code @WritePolicy} 注解的 Bean 并注册到 MemoryWriteManager。
 *
 * <p>执行顺序：LOWEST_PRECEDENCE - 90，在存储后端注册之后。
 * 校验实现接口→提取适用层级→注册策略→设置默认策略。</p>
 */
public class WritePolicyPostProcessor implements BeanPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(WritePolicyPostProcessor.class);

    private final DefaultMemoryWriteManager writeManager;

    /**
     * 构造写策略后处理器，注入 DefaultMemoryWriteManager 依赖。
     *
     * <p>DefaultMemoryWriteManager 是记忆系统的写操作管理中心，本处理器发现的每个
     * @WritePolicy 注解 Bean 最终都会通过 {@code writeManager.register()} 方法注册到
     * 该管理器中。通过构造器注入确保写管理器在处理器激活前已由 StorageAutoConfiguration
     * 创建完成。</p>
     *
     * @param writeManager 记忆写管理器实例，由 StorageAutoConfiguration 创建并注入
     */
    public WritePolicyPostProcessor(DefaultMemoryWriteManager writeManager) {
        this.writeManager = writeManager;
    }

    /**
     * 返回此 BeanPostProcessor 的执行顺序值，数值越小优先级越高。
     *
     * <p>返回 {@code Ordered.LOWEST_PRECEDENCE - 90}，确保在 StorageBackendPostProcessor
     * （优先级 -100）之后执行。这个顺序设计保证了：存储后端已经完成注册和初始化，
     * 记忆系统的基础设施已就绪，写策略可以安全地引用已注册的存储后端。同时为
     * MemorySystemAutoConfigurator（优先级 -50）的后续编织工作预留了空间。</p>
     *
     * @return {@link Ordered#LOWEST_PRECEDENCE} - 90
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 90;
    }

    /**
     * Spring Bean 后处理器核心方法，在 Bean 初始化完成后被容器调用，负责发现和注册写策略。
     *
     * <p><b>处理流程：</b></p>
     * <ol>
     *   <li><b>注解检测：</b>检查 Bean 类上是否有 {@code @WritePolicy} 注解，无注解则跳过。</li>
     *   <li><b>接口校验：</b>强制要求标注了 @WritePolicy 的 Bean 必须实现 MemoryPersistence
     *       接口，未实现则抛出 {@link LyClawException} 异常（STORAGE_ERROR），防止后续
     *       写入操作因类型转换失败而崩溃。</li>
     *   <li><b>适用层级提取：</b>从注解的 {@code applicableLayers()} 属性中提取此写策略
     *       适用的 {@link MemoryLayer} 层级列表，存入 HashSet 以便快速查找。</li>
     *   <li><b>策略注册：</b>通过 {@code writeManager.register()} 将策略按名称注册到
     *       DefaultMemoryWriteManager 中，同时记录适用的层级信息。</li>
     *   <li><b>默认策略设置：</b>如果注解的 {@code defaultPolicy} 为 true，遍历所有适用
     *       层级，为每个层级设置此策略为默认策略。这意味着后续对该层级的写入操作默认
     *       使用此策略，除非调用方显式指定其他策略名称。</li>
     * </ol>
     *
     * @param bean Spring 容器中已初始化的 Bean 实例，可能带有 @WritePolicy 注解
     * @param beanName Bean 在 Spring 容器中的注册名称，用于日志和异常信息
     * @return 始终返回原始 bean 实例
     * @throws BeansException 当 @WritePolicy 标注的 Bean 未实现 MemoryPersistence 接口时抛出
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        WritePolicy annotation = bean.getClass().getAnnotation(WritePolicy.class);
        if (annotation == null) return bean;

        if (!(bean instanceof MemoryPersistence)) {
            throw new LyClawException(
                    ErrorCode.STORAGE_ERROR.code(), ErrorCode.STORAGE_ERROR.httpStatus(),
                    "类 " + bean.getClass().getName() + " 标注了 @WritePolicy 但未实现 MemoryPersistence 接口");
        }

        MemoryPersistence policy = (MemoryPersistence) bean;
        Set<MemoryLayer> layers = new HashSet<>(Arrays.asList(annotation.applicableLayers()));

        writeManager.register(annotation.name(), policy, layers);
        log.info("注册写策略: {} (适用层级: {}, 默认: {})", annotation.name(), layers, annotation.defaultPolicy());

        if (annotation.defaultPolicy()) {
            for (MemoryLayer layer : layers) {
                writeManager.setLayerDefault(layer, annotation.name());
            }
        }

        return bean;
    }
}
