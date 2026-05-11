package lyjew.com.lyclaw.provider;

import lyjew.com.lyclaw.adapter.ModelAdapter;

import java.util.Set;

/**
 * 模型提供商接口，负责管理和分发不同LLM提供商的适配器实例。
 * 该类已废弃，请使用新的配置体系。
 */
@Deprecated
public interface ModelProvider {

    /**
     * 根据提供商名称获取对应的模型适配器。
     *
     * @param provider 提供商名称
     * @return 对应的模型适配器实例
     */
    ModelAdapter getAdapter(String provider);

    /**
     * 获取系统默认的提供商名称。
     *
     * @return 默认提供商名称
     */
    String getDefaultProvider();

    /**
     * 获取当前已完成配置的适配器实例。
     *
     * @return 已配置的适配器
     */
    ModelAdapter getConfiguredAdapter();

    /**
     * 列出当前所有可用的提供商名称集合。
     *
     * @return 提供商名称集合
     */
    Set<String> listProviders();

    /**
     * 刷新适配器注册信息，重新加载配置或检测新提供商。
     */
    void refresh();
}
