package lyjew.com.lyclaw.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.adapter.factory.ModelAdapterFactory;
import lyjew.com.lyclaw.model.ModelConfig;
import lyjew.com.lyclaw.storage.ConfigStorage;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads provider configurations from application.yaml and initializes adapters at startup.
 *
 * <p>Configures providers listed under {@code lyclaw.providers.*} in application.yaml.
 * For each provider, saves the config to ConfigStorage and calls factory.getConfiguredAdapter()
 * so the adapter is ready to use when the first request arrives.</p>
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "lyclaw")
public class ProviderInitializer {

    private final ConfigStorage configStorage;
    private final ModelAdapterFactory adapterFactory;

    /** Map of provider-name → ModelConfig, populated from application.yaml */
    private Map<String, ModelConfig> providers = new HashMap<>();

    public ProviderInitializer(ConfigStorage configStorage, ModelAdapterFactory adapterFactory) {
        this.configStorage = configStorage;
        this.adapterFactory = adapterFactory;
    }

    public Map<String, ModelConfig> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ModelConfig> providers) {
        this.providers = providers;
    }

    @PostConstruct
    public void initProviders() {
        if (providers.isEmpty()) {
            log.warn("No providers configured in application.yaml under lyclaw.providers.*");
            return;
        }

        for (Map.Entry<String, ModelConfig> entry : providers.entrySet()) {
            String providerName = entry.getKey();
            ModelConfig cfg = entry.getValue();

            if (!cfg.isEnabled()) {
                log.info("Provider [{}] is disabled, skipping", providerName);
                continue;
            }

            // Ensure provider name is set on the config
            cfg.setProvider(providerName);
            if (cfg.getId() == null || cfg.getId().isBlank()) {
                cfg.setId("cfg-" + providerName);
            }
            cfg.setName(providerName);
            cfg.setCreatedAt(LocalDateTime.now());
            cfg.setUpdatedAt(LocalDateTime.now());

            // Persist config to storage
            configStorage.save(cfg);
            log.info("Saved provider config: {} (model={}, baseUrl={})",
                    providerName, cfg.getModel(), cfg.getBaseUrl());

            // Initialize the adapter
            try {
                adapterFactory.getConfiguredAdapter(cfg);
                log.info("Adapter [{}] initialized successfully", providerName);
            } catch (Exception e) {
                log.error("Failed to initialize adapter [{}]: {}", providerName, e.getMessage());
            }
        }
    }
}
