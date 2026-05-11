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

    public WritePolicyPostProcessor(DefaultMemoryWriteManager writeManager) {
        this.writeManager = writeManager;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 90;
    }

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
