package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import lyjew.com.lyclaw.storage.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Map;

/**
 * 存储层自动配置——注册框架必需的存储组件 Bean。
 *
 * <p>所有组件使用 @ConditionalOnMissingBean，允许使用者自定义覆盖。
 * InMemoryBackend 和 FileBackend 作为兜底实现永远可用。
 */
@AutoConfiguration
public class StorageAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StorageAutoConfiguration.class);

    /**
     * 注册 StorageProperties 配置属性 Bean，绑定 {@code lyclaw.storage} 前缀的配置项。
     *
     * <p>通过 {@code @ConfigurationProperties} 注解，Spring Boot 自动将配置文件中以
     * {@code lyclaw.storage} 为前缀的属性绑定到此对象的字段上，包括各存储层的默认后端映射
     * （stores 字段）和各后端的连接配置（backends 字段，如 FileBackend 的数据目录路径、
     * 数据库连接 URL 等）。使用 {@code @ConditionalOnMissingBean} 允许用户覆盖。</p>
     *
     * @return StorageProperties 实例，其字段由 Spring Boot 从配置中自动填充
     */
    @Bean
    @ConditionalOnMissingBean
    @ConfigurationProperties(prefix = "lyclaw.storage")
    public StorageProperties storageProperties() {
        return new StorageProperties();
    }

    /**
     * 注册 StorageBackendRegistry 存储后端注册表 Bean，作为所有存储后端的中央注册中心。
     *
     * <p>StorageBackendRegistry 是 LyClaw 存储架构的核心组件，负责维护后端名称到
     * StorageBackend 实例的映射，同时管理存储层（StoreLayer）到默认后端的映射关系。
     * 框架中的存储系统采用多层架构——SESSION 层、ENTITY 层、MEMORY 层各有独立的默认后端，
     * 通过此注册表的层级映射实现请求的自动路由。使用 {@code @ConditionalOnMissingBean}
     * 允许高级用户提供自定义的注册表实现。</p>
     *
     * @return DefaultStorageBackendRegistry 实例，内置 HashMap 存储后端映射
     */
    @Bean
    @ConditionalOnMissingBean
    public StorageBackendRegistry storageBackendRegistry() {
        return new DefaultStorageBackendRegistry();
    }

    /**
     * 注册 DefaultMemoryWriteManager 记忆写入管理器 Bean，管理记忆持久化策略。
     *
     * <p>DefaultMemoryWriteManager 是记忆系统的写操作编排中心，负责管理多个
     * MemoryPersistence 写策略实例的注册和查找。它维护策略名称到 MemoryPersistence
     * 实现的映射，以及 MemoryLayer 到默认策略名称的映射。当记忆系统需要将数据写入
     * 持久化存储时，通过此管理器查找合适的写策略并执行写入操作。支持按名称和按层级
     * 两种方式查找策略，并可通过配置或注解动态注册新的写策略。</p>
     *
     * @return DefaultMemoryWriteManager 实例，初始化为空的策略映射
     */
    @Bean
    @ConditionalOnMissingBean
    public DefaultMemoryWriteManager memoryWriteManager() {
        return new DefaultMemoryWriteManager();
    }

    /**
     * 注册 InMemoryBackend 内存存储后端 Bean，作为框架的内置兜底存储实现。
     *
     * <p>InMemoryBackend 是基于 JVM 堆内存的存储后端，所有数据存储在 ConcurrentHashMap
     * 中，进程重启后数据丢失。它作为 LyClaw 框架的默认兜底后端，确保即使没有任何外部
     * 存储系统（数据库、文件系统等）配置，记忆系统也能正常工作。Bean 名称为
     * {@code "inMemoryBackend"}，使用 {@code @ConditionalOnMissingBean} 确保用户
     * 可以用自己的实现覆盖。初始化时传入空配置 Map，因为内存后端的名字在注册时由
     * StorageBackendPostProcessor 根据注解动态设置。</p>
     *
     * @return 已初始化的 InMemoryBackend 实例，数据存储在 JVM 堆内存中
     */
    @Bean
    @ConditionalOnMissingBean(name = "inMemoryBackend")
    public StorageBackend inMemoryBackend() {
        InMemoryBackend backend = new InMemoryBackend();
        backend.initialize(java.util.Map.of());
        return backend;
    }

    /**
     * 注册 FileBackend 文件存储后端 Bean，提供基于本地文件系统的持久化存储能力。
     *
     * <p>FileBackend 将记忆数据以文件形式存储到本地磁盘上，支持进程重启后数据恢复。
     * 存储格式通常为 JSON 文件或目录结构。数据存储目录通过 {@code lyclaw.storage.backends.file.url}
     * 配置项指定（如 {@code file:///data/lyclaw/memory}），如果未配置则使用 FileBackend
     * 自身的默认路径。Bean 名称为 {@code "fileBackend"}，同样使用 {@code @ConditionalOnMissingBean}
     * 允许替换。初始化时会将配置中的 dataDir 路径传递给后端。</p>
     *
     * @param properties StorageProperties 实例，提供文件后端的连接配置信息
     * @return 已按配置初始化的 FileBackend 实例，数据持久化到本地文件系统
     */
    @Bean
    @ConditionalOnMissingBean(name = "fileBackend")
    public StorageBackend fileBackend(StorageProperties properties) {
        FileBackend backend = new FileBackend();
        java.util.Map<String, Object> config = new java.util.HashMap<>();
        // base-path 优先级最高，其次是 backends.file.url，未配置则用 FileBackend 内置默认值
        if (properties.getBasePath() != null && !properties.getBasePath().isBlank()) {
            config.put("dataDir", properties.getBasePath());
        } else {
            StorageProperties.BackendConfig backendConfig = properties.getBackends().get("file");
            if (backendConfig != null && backendConfig.getUrl() != null) {
                config.put("dataDir", backendConfig.getUrl());
            }
        }
        backend.initialize(config);
        return backend;
    }

    /**
     * 注册 StorageFacade 存储门面 Bean，作为存储操作的统一入口。
     *
     * <p>StorageFacade 封装了底层存储后端的复杂性，为上层业务代码提供简洁的存储 API。
     * DefaultStorageFacade 实现通过 StorageBackendRegistry 动态查找目标后端，支持
     * 按存储层（StoreLayer）自动路由存储请求到对应的后端实例。上层代码无需关心数据
     * 实际存储在内存还是文件系统，只需通过门面接口调用即可。使用
     * {@code @ConditionalOnMissingBean} 允许用户完全替换存储门面实现。</p>
     *
     * @param registry StorageBackendRegistry 实例，提供后端查找和路由能力
     * @return DefaultStorageFacade 实例，封装了后端查找和存储操作委托逻辑
     */
    @Bean
    @ConditionalOnMissingBean
    public StorageFacade storageFacade(StorageBackendRegistry registry) {
        return new DefaultStorageFacade(registry);
    }

    /**
     * 将 StorageProperties 中的层→后端映射显式应用到注册表。
     * 这与 StorageBackendPostProcessor 的注解扫描互补，确保配置驱动的映射始终生效。
     * 同时确保配置引用的后端实例已存在于注册表中（兜底手动注册）。
     */
    @Bean
    public InitializingBean storageLayerDefaultsInitializer(
            StorageBackendRegistry registry,
            StorageProperties properties,
            StorageBackend inMemoryBackend,
            StorageBackend fileBackend) {
        return () -> {
            if (!(registry instanceof DefaultStorageBackendRegistry reg)) return;

            // 兜底：确保两个内置后端已注册（防止 BeanPostProcessor 未执行）
            Map<String, StorageBackend> builtins = Map.of(
                    "inmemory", inMemoryBackend,
                    "file", fileBackend);
            for (Map.Entry<String, StorageBackend> be : builtins.entrySet()) {
                if (registry.get(be.getKey()) == null) {
                    log.info("兜底注册存储后端: {} ({}), 注解扫描可能未执行",
                            be.getKey(), be.getValue().getClass().getSimpleName());
                    // 从注解提取层级信息
                    java.util.Set<StoreLayer> layers = java.util.EnumSet.noneOf(StoreLayer.class);
                    Class<?> clz = be.getValue().getClass();
                    if (clz.isAnnotationPresent(lyjew.com.lyclaw.annotation.storage.SessionStore.class))
                        layers.add(StoreLayer.SESSION);
                    if (clz.isAnnotationPresent(lyjew.com.lyclaw.annotation.storage.EntityStore.class))
                        layers.add(StoreLayer.ENTITY);
                    if (clz.isAnnotationPresent(lyjew.com.lyclaw.annotation.storage.MemoryStore.class))
                        layers.add(StoreLayer.MEMORY);
                    reg.register(be.getKey(), be.getValue(), layers, 0);
                }
            }

            // 应用配置中的层→后端映射
            for (Map.Entry<String, String> entry : properties.getStores().entrySet()) {
                try {
                    StoreLayer layer = StoreLayer.valueOf(entry.getKey().toUpperCase());
                    reg.setLayerDefault(layer, entry.getValue());
                    log.info("存储层 {} 默认后端设置为: {}", layer, entry.getValue());
                } catch (IllegalArgumentException e) {
                    log.warn("未知存储层: {}", entry.getKey());
                }
            }
        };
    }
}
