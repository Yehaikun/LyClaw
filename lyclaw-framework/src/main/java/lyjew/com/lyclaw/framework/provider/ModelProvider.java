package lyjew.com.lyclaw.framework.provider;

import lyjew.com.lyclaw.framework.adapter.ModelAdapter;

import java.util.Set;

public interface ModelProvider {

    ModelAdapter getAdapter(String provider);
    String getDefaultProvider();
    ModelAdapter getConfiguredAdapter();
    Set<String> listProviders();
    void refresh();
}
