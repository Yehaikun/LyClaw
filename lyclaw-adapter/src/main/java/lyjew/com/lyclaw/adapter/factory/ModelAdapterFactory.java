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

/**
 * 模型适配器工厂，负责管理和提供不同模型厂商的适配器实例。
 *
 * <p>在 Spring 容器启动时（{@link PostConstruct}），自动扫描并注册所有
 * 实现了 {@link ModelAdapter} 接口的 Bean。每个适配器通过其
 * {@link ModelAdapter#getProvider()} 返回的厂商名称作为键存入内部映射表。</p>
 *
 * <p>主要功能：</p>
 * <ul>
 *   <li>自动发现并注册所有 ModelAdapter 实现</li>
 *   <li>根据厂商名称查找对应的适配器</li>
 *   <li>根据 ModelConfig 配置获取并初始化适配器</li>
 *   <li>支持刷新、列表和存在性检查等操作</li>
 * </ul>
 */
@Slf4j
@Component
public class ModelAdapterFactory {

    /** 线程安全的适配器映射表，键为厂商名称，值为对应的适配器实例 */
    private final Map<String, ModelAdapter> adapterMap = new ConcurrentHashMap<>();
    private final ApplicationContext context;

    public ModelAdapterFactory(ApplicationContext context) {
        this.context = context;
    }

    /**
     * Spring 容器初始化后自动执行，扫描并注册所有 ModelAdapter Bean。
     *
     * <p>遍历 Spring 容器中所有 ModelAdapter 类型的 Bean，获取其
     * provider 名称并注册到 adapterMap 中。如果某个适配器未设置 provider，
     * 则输出警告并跳过。</p>
     */
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

    /**
     * 根据厂商名称获取对应的适配器。
     *
     * @param provider 厂商名称，不能为空
     * @return 对应的 ModelAdapter 实例
     * @throws ModelException 当 provider 为空或找不到对应适配器时抛出
     */
    public ModelAdapter getAdapter(String provider) {
        if (provider == null || provider.isEmpty()) {
            throw ErrorCode.ADAPTER_NOT_FOUND.exception("provider 不能为空");
        }

        ModelAdapter adapter = adapterMap.get(provider);
        if (adapter == null) {
            // 找不到时抛出异常，附上已注册的厂商列表方便排查
            throw ErrorCode.ADAPTER_NOT_FOUND.exception(
                    "未找到厂商 [" + provider + "] 的适配器，已注册: " + adapterMap.keySet());
        }

        return adapter;
    }

    /**
     * 根据模型配置获取并初始化适配器。
     *
     * <p>先从配置中提取 provider 名称查找适配器，然后调用
     * {@link ModelAdapter#configure(ModelConfig)} 进行配置初始化。</p>
     *
     * @param config 模型配置，包含厂商名称、API Key、模型名等信息
     * @return 配置完成后的适配器实例
     * @throws ModelException 当配置为 null 或找不到适配器时抛出
     */
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

    /**
     * 安全地查找适配器，不存在时返回空 Optional 而不是抛出异常。
     *
     * @param provider 厂商名称
     * @return 包含适配器的 Optional，不存在时为 {@link Optional#empty()}
     */
    public Optional<ModelAdapter> findAdapter(String provider) {
        return Optional.ofNullable(adapterMap.get(provider));
    }

    /**
     * 列出所有已注册的厂商名称。
     *
     * @return 不可修改的厂商名称集合
     */
    public Set<String> listProviders() {
        return Collections.unmodifiableSet(adapterMap.keySet());
    }

    /**
     * 检查指定厂商是否已注册。
     *
     * @param provider 厂商名称
     * @return true 表示该厂商的适配器已注册
     */
    public boolean hasProvider(String provider) {
        return adapterMap.containsKey(provider);
    }

    /**
     * 获取已注册的适配器数量。
     *
     * @return 适配器数量
     */
    public int getAdapterCount() {
        return adapterMap.size();
    }

    /**
     * 刷新适配器注册表，清空后重新扫描 Spring 容器。
     *
     * <p>适用于运行时动态加载新适配器的场景。</p>
     */
    public void refresh() {
        adapterMap.clear();
        init();
        log.info("适配器工厂已刷新");
    }
}
