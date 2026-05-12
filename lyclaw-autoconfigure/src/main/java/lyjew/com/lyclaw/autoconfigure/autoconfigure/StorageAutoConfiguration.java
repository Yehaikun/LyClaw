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

    @Bean
    @ConditionalOnMissingBean
    @ConfigurationProperties(prefix = "lyclaw.storage")
    public StorageProperties storageProperties() {
        return new StorageProperties();
    }

    @Bean
    @ConditionalOnMissingBean
    public StorageBackendRegistry storageBackendRegistry() {
        return new DefaultStorageBackendRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultMemoryWriteManager memoryWriteManager() {
        return new DefaultMemoryWriteManager();
    }

    @Bean
    @ConditionalOnMissingBean(name = "inMemoryBackend")
    public StorageBackend inMemoryBackend() {
        InMemoryBackend backend = new InMemoryBackend();
        backend.initialize(java.util.Map.of());
        return backend;
    }

    @Bean
    @ConditionalOnMissingBean(name = "fileBackend")
    public StorageBackend fileBackend(StorageProperties properties) {
        FileBackend backend = new FileBackend();
        java.util.Map<String, Object> config = new java.util.HashMap<>();
        StorageProperties.BackendConfig backendConfig = properties.getBackends().get("file");
        if (backendConfig != null && backendConfig.getUrl() != null) {
            config.put("dataDir", backendConfig.getUrl());
        }
        backend.initialize(config);
        return backend;
    }

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
