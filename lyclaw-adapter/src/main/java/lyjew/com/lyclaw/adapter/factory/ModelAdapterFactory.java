package lyjew.com.lyclaw.adapter.factory;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.enums.ErrorCode;
import lyjew.com.lyclaw.exception.ModelException;
import lyjew.com.lyclaw.model.ModelConfig;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ModelAdapterFactory {

    private final Map<String, ModelAdapter> adapterMap = new ConcurrentHashMap<>();
    private final ApplicationContext context;

    public ModelAdapterFactory(ApplicationContext context) {
        this.context = context;
    }

    @PostConstruct
    public void init() {
        Map<String, ModelAdapter> beans = context.getBeansOfType(ModelAdapter.class);

        if (beans.isEmpty()) {
            log.warn("未找到任何 ModelAdapter 实现，请检查 adapter 包扫描配置");
            return;
        }

        for (Map.Entry<String, ModelAdapter> entry : beans.entrySet()) {
            ModelAdapter adapter = entry.getValue();
            String provider = adapter.getProvider();

            if (provider == null || provider.isEmpty()) {
                log.warn("跳过未设置 provider 的适配器: {}", entry.getKey());
                continue;
            }

            adapterMap.put(provider, adapter);
            log.info("注册适配器: [{}] -> {}", provider, adapter.getClass().getSimpleName());
        }

        log.info("适配器工厂初始化完成，共注册 {} 个适配器: {}", adapterMap.size(), adapterMap.keySet());
    }

    public ModelAdapter getAdapter(String provider) {
        if (provider == null || provider.isEmpty()) {
            throw ErrorCode.ADAPTER_NOT_FOUND.exception("provider 不能为空");
        }

        ModelAdapter adapter = adapterMap.get(provider);
        if (adapter == null) {
            throw ErrorCode.ADAPTER_NOT_FOUND.exception(
                    "未找到厂商 [" + provider + "] 的适配器，已注册: " + adapterMap.keySet());
        }

        return adapter;
    }

    public ModelAdapter getConfiguredAdapter(ModelConfig config) {
        if (config == null) {
            throw ErrorCode.ADAPTER_NOT_FOUND.exception("ModelConfig 为 null");
        }

        ModelAdapter adapter = getAdapter(config.getProvider());
        adapter.configure(config);
        log.info("适配器已配置: provider={}, model={}, baseUrl={}",
                config.getProvider(), adapter.getModel(), adapter.getBaseUrl());
        return adapter;
    }

    public Optional<ModelAdapter> findAdapter(String provider) {
        return Optional.ofNullable(adapterMap.get(provider));
    }

    public Set<String> listProviders() {
        return Collections.unmodifiableSet(adapterMap.keySet());
    }

    public boolean hasProvider(String provider) {
        return adapterMap.containsKey(provider);
    }

    public int getAdapterCount() {
        return adapterMap.size();
    }

    public void refresh() {
        adapterMap.clear();
        init();
        log.info("适配器工厂已刷新");
    }
}
