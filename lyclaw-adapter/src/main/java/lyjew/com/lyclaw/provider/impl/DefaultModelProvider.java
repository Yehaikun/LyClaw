package lyjew.com.lyclaw.provider.impl;

import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.provider.ModelProvider;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;

@Component
public class DefaultModelProvider implements ModelProvider {

    @Override
    public ModelAdapter getAdapter(String provider) {
        return null;
    }

    @Override
    public String getDefaultProvider() {
        return "none";
    }

    @Override
    public ModelAdapter getConfiguredAdapter() {
        return null;
    }

    @Override
    public Set<String> listProviders() {
        return Collections.emptySet();
    }

    @Override
    public void refresh() {
    }
}
