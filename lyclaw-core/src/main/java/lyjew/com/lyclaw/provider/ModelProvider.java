package lyjew.com.lyclaw.provider;

import lyjew.com.lyclaw.adapter.ModelAdapter;

import java.util.Set;

@Deprecated
public interface ModelProvider {

    ModelAdapter getAdapter(String provider);
    String getDefaultProvider();
    ModelAdapter getConfiguredAdapter();
    Set<String> listProviders();
    void refresh();
}
