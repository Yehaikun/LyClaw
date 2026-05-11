package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import lyjew.com.lyclaw.storage.*;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 存储层自动配置——注册框架必需的存储组件 Bean。
 *
 * <p>所有组件使用 @ConditionalOnMissingBean，允许使用者自定义覆盖。
 * InMemoryBackend 和 FileBackend 作为兜底实现永远可用。
 */
@AutoConfiguration
public class StorageAutoConfiguration {

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
}
