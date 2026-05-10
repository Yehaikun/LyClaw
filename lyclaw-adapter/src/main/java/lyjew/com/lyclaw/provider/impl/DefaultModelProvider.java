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
        throw new UnsupportedOperationException(
                "默认 ModelProvider 不提供具体适配器。请在 lyclaw-adapter 模块中"
                        + "实现 ModelProvider 接口并注册为 @Component，"
                        + "或在 application.yml 中配置 lyclaw.engine.default-provider。");
    }

    @Override
    public String getDefaultProvider() {
        return "未配置";
    }

    @Override
    public ModelAdapter getConfiguredAdapter() {
        throw new UnsupportedOperationException(
                "默认 ModelProvider 不提供具体适配器。请在 lyclaw-adapter 模块中"
                        + "实现 ModelProvider 接口并注册为 @Component，"
                        + "或在 application.yml 中配置 lyclaw.engine.default-provider。");
    }

    @Override
    public Set<String> listProviders() {
        return Collections.emptySet();
    }

    @Override
    public void refresh() {
    }
}
