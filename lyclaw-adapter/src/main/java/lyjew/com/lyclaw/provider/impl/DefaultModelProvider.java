package lyjew.com.lyclaw.provider.impl;

import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.provider.ModelProvider;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;

/**
 * {@link ModelProvider} 的默认空实现，所有方法均返回空值或空集合。
 *
 * <p>该类作为 Spring 的默认 Bean 注册，确保在没有明确配置模型厂商的情况下
 * 应用能够正常启动而不会因为缺少 Bean 而报错。实际使用时，
 * 应由具体的实现类（如数据库配置驱动或属性文件驱动）来覆盖这些方法。</p>
 *
 * <p>所有方法返回 null 或空集合，表示"未配置任何模型提供商"。</p>
 */
@Component
public class DefaultModelProvider implements ModelProvider {

    /**
     * 默认返回 null，表示没有对应厂商的适配器。
     *
     * @param provider 提供商名称
     * @return 始终返回 null
     */
    @Override
    public ModelAdapter getAdapter(String provider) {
        return null;
    }

    /**
     * 返回默认提供商标识 "none"。
     *
     * @return 字符串 "none"
     */
    @Override
    public String getDefaultProvider() {
        return "none";
    }

    /**
     * 返回当前已配置的适配器，默认无配置。
     *
     * @return 始终返回 null
     */
    @Override
    public ModelAdapter getConfiguredAdapter() {
        return null;
    }

    /**
     * 列出所有可用的提供商名称，默认为空。
     *
     * @return 空集合
     */
    @Override
    public Set<String> listProviders() {
        return Collections.emptySet();
    }

    /**
     * 刷新提供商配置，默认无操作。
     */
    @Override
    public void refresh() {
    }
}
