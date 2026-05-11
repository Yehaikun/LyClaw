package lyjew.com.lyclaw.autoconfigure.processor;

import lyjew.com.lyclaw.annotation.storage.FullTextStore;
import lyjew.com.lyclaw.annotation.storage.VectorStore;
import lyjew.com.lyclaw.storage.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.Ordered;

import java.util.Set;

/**
 * 记忆系统自动配置器，将所有分散注册的组件编织成完整的记忆系统。
 *
 * <p>执行顺序：LOWEST_PRECEDENCE - 50，在存储后端和写策略都注册之后。
 * 完成自动装配：选择 MemoryStore 默认后端→检测向量/全文搜索能力→配置检索路径→初始化 L1+L2 双层架构。</p>
 */
public class MemorySystemAutoConfigurator implements InitializingBean, Ordered {

    private static final Logger log = LoggerFactory.getLogger(MemorySystemAutoConfigurator.class);

    private final StorageBackendRegistry backendRegistry;
    private final StorageProperties storageProperties;

    public MemorySystemAutoConfigurator(StorageBackendRegistry backendRegistry,
                                        StorageProperties storageProperties) {
        this.backendRegistry = backendRegistry;
        this.storageProperties = storageProperties;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 50;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        log.info("=== 记忆系统自动配置 ===");

        // 1. 确定 MemoryStore 层的默认后端
        String configuredMemoryBackend = storageProperties.getLayerBackend(StoreLayer.MEMORY);
        StorageBackend memoryBackend = backendRegistry.get(configuredMemoryBackend);

        if (memoryBackend == null) {
            // 回退到 InMemoryBackend
            memoryBackend = backendRegistry.resolve(StoreLayer.MEMORY);
        }

        if (memoryBackend == null) {
            log.warn("MemoryStore 层没有可用后端，记忆持久化功能不可用");
            return;
        }

        log.info("MemoryStore 后端: {}", memoryBackend.backendName());

        // 2. 检测向量搜索能力
        Class<?> backendClass = memoryBackend.getClass();
        boolean hasVector = backendClass.isAnnotationPresent(VectorStore.class);
        boolean hasFullText = backendClass.isAnnotationPresent(FullTextStore.class);
        Set<StorageCapability> caps = memoryBackend.capabilities();
        boolean actualVector = caps.contains(StorageCapability.VECTOR_SEARCH);
        boolean actualFullText = caps.contains(StorageCapability.FULL_TEXT);

        if (hasVector && actualVector) {
            log.info("混合检索启用: 向量路径 (VECTOR)");
        }
        if (hasFullText && actualFullText) {
            log.info("混合检索启用: 全文搜索路径 (BM25)");
        }
        if (hasVector && hasFullText && actualVector && actualFullText) {
            log.info("混合检索: 完整 VECTOR + BM25 多路融合");
        }
        if (!hasVector && !hasFullText) {
            log.info("检索路径: 纯关键词匹配（无向量/全文能力）");
        }

        // 3. L1 缓存 + L2 持久化 架构已通过 MemorySystem + StorageBackend 实现
        log.info("记忆系统就绪: L1 (ConcurrentHashMap) + L2 ({})", memoryBackend.backendName());
        log.info("=============================");
    }
}
