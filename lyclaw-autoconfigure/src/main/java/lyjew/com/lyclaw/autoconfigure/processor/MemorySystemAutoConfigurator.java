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

    /**
     * 构造记忆系统自动配置器，注入 StorageBackendRegistry 和 StorageProperties 依赖。
     *
     * <p>StorageBackendRegistry 提供所有已注册存储后端的查询能力，用于确定 MemoryStore
     * 层的默认后端和检测后端的能力声明；StorageProperties 提供层到后端的配置映射，
     * 允许在配置文件中指定各存储层使用的后端实例。通过构造器注入确保两个依赖在
     * 配置器激活前都已由 StorageAutoConfiguration 创建并完成初始化。</p>
     *
     * @param backendRegistry 存储后端注册表实例，提供后端查询和层级解析能力
     * @param storageProperties 存储配置属性实例，提供配置驱动的层到后端映射
     */
    public MemorySystemAutoConfigurator(StorageBackendRegistry backendRegistry,
                                        StorageProperties storageProperties) {
        this.backendRegistry = backendRegistry;
        this.storageProperties = storageProperties;
    }

    /**
     * 返回此 InitializingBean 的执行顺序值，数值越小优先级越高。
     *
     * <p>返回 {@code Ordered.LOWEST_PRECEDENCE - 50}，在所有 BeanPostProcessor
     * 和自动配置器之后执行。这个顺序确保：存储后端已完全注册（StorageBackendPostProcessor
     * 在 -100）、写策略已注册（WritePolicyPostProcessor 在 -90）、所有注解扫描都已完成，
     * 记忆系统的最终装配可以在所有组件都就绪的稳定状态下进行。</p>
     *
     * @return {@link Ordered#LOWEST_PRECEDENCE} - 50
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 50;
    }

    /**
     * InitializingBean 回调方法，在所有 Bean 属性设置完成后由 Spring 容器调用，
     * 负责将分散注册的存储组件编织成完整的记忆系统。
     *
     * <p><b>装配流程：</b></p>
     * <ol>
     *   <li><b>确定 MemoryStore 默认后端：</b>首先从 StorageProperties 的
     *       {@code lyclaw.storage.stores.memory} 配置中读取指定的后端名称，
     *       然后通过 StorageBackendRegistry 查找对应的后端实例。如果配置未指定
     *       或指定的后端不存在，则回退到 {@code registry.resolve(StoreLayer.MEMORY)}
     *       自动解析默认后端。若仍无可用后端，输出警告日志并跳过后续配置，
     *       此时记忆持久化功能不可用但系统仍可正常启动。</li>
     *   <li><b>检索能力检测：</b>检查 MemoryStore 后端类上是否有 @VectorStore 和
     *       @FullTextStore 注解声明，并通过后端实例的 {@code capabilities()} 方法
     *       获取实际支持的能力集合。对交叉校验结果进行分类输出——仅向量搜索、
     *       仅全文搜索、向量+全文混合检索、纯关键词匹配四种模式，各有对应的日志输出。</li>
     *   <li><b>双层架构确认：</b>输出 L1（ConcurrentHashMap 内存缓存）+ L2（持久化后端）
     *       双层存储架构的就绪日志，表明记忆系统已完成装配。</li>
     * </ol>
     *
     * <p><b>注意事项：</b>此方法不执行实际的数据操作，仅完成架构装配和能力检测。
     * 实际的读写操作由 MemorySystem、DefaultMemoryWriteManager 和 StorageFacade
     * 等组件在运行时协作完成。</p>
     *
     * @throws Exception 当装配过程中发生不可恢复的错误时抛出
     */
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
