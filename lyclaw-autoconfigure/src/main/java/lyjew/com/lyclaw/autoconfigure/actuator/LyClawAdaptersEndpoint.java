package lyjew.com.lyclaw.autoconfigure.actuator;

import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.autoconfigure.processor.AdapterAnnotationProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes available model adapters and the active provider.
 */
@Endpoint(id = "lyclaw-adapters")
public class LyClawAdaptersEndpoint {

    private final AdapterAnnotationProcessor processor;

    @Autowired
    public LyClawAdaptersEndpoint(@Autowired(required = false) AdapterAnnotationProcessor processor) {
        this.processor = processor;
    }

    @ReadOperation
    public Map<String, Object> adapters() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (processor == null) {
            result.put("available", false);
            result.put("reason", "No AdapterAnnotationProcessor bean registered");
            return result;
        }

        result.put("availableProviders", processor.getAvailableProviders());

        List<ModelAdapter> all = processor.getAllAdapters();
        result.put("adapterCount", all.size());
        result.put("adapters", all.stream()
                .map(adapter -> {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("provider", adapter.getProvider());
                    a.put("model", adapter.getModel());
                    a.put("baseUrl", adapter.getBaseUrl());
                    a.put("configured", adapter.isConfigured());
                    a.put("class", adapter.getClass().getName());
                    return a;
                })
                .toList());

        return result;
    }
}
