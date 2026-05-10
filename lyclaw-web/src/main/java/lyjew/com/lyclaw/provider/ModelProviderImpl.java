package lyjew.com.lyclaw.provider;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.adapter.factory.ModelAdapterFactory;
import lyjew.com.lyclaw.config.EngineProperties;
import lyjew.com.lyclaw.model.ModelConfig;
import lyjew.com.lyclaw.storage.ConfigStorage;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * ModelProvider implementation -- adapts ModelAdapterFactory to the ModelProvider interface.
 *
 * <p>Marked @Primary so Spring prefers this bean when multiple ModelProvider candidates exist.</p>
 *
 * <p>Fixed: getConfiguredAdapter() now properly loads config from ConfigStorage
 * and calls adapter.configure() before returning the adapter.
 * The default provider is read from EngineProperties instead of being hardcoded.</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
@Slf4j
@Primary
@Component
public class ModelProviderImpl implements ModelProvider {

    private final ModelAdapterFactory adapterFactory;
    private final ConfigStorage configStorage;
    private final EngineProperties engineProperties;

    public ModelProviderImpl(ModelAdapterFactory adapterFactory,
                             ConfigStorage configStorage,
                             EngineProperties engineProperties) {
        this.adapterFactory = adapterFactory;
        this.configStorage = configStorage;
        this.engineProperties = engineProperties;
    }

    @Override
    public ModelAdapter getAdapter(String provider) {
        return adapterFactory.getAdapter(provider);
    }

    @Override
    public String getDefaultProvider() {
        return engineProperties.getDefaultProvider();
    }

    @Override
    public ModelAdapter getConfiguredAdapter() {
        String provider = getDefaultProvider();
        ModelAdapter adapter = adapterFactory.getAdapter(provider);

        // Load config from storage and configure the adapter
        configStorage.get(provider).ifPresentOrElse(
            config -> {
                adapter.configure(config);
                log.debug("Adapter [{}] configured from stored config", provider);
            },
            () -> log.warn("No stored config found for provider [{}], adapter is NOT configured", provider)
        );

        return adapter;
    }

    @Override
    public Set<String> listProviders() {
        return adapterFactory.listProviders();
    }

    @Override
    public void refresh() {
        adapterFactory.refresh();
    }
}