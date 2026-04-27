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
 * 模型适配器工厂 + 注册表
 *
 * 管理所有厂商适配器的生命周期，提供统一的获取接口。
 *
 * 设计模式：工厂模式 + 注册表模式
 * - 启动时自动扫描所有 ModelAdapter 的 Spring Bean
 * - 按 provider 名称注册到内部 Map
 * - 上层业务通过 provider 名称获取适配器
 *
 * 使用方式：
 *   ModelAdapter adapter = factory.getAdapter("minimax");
 *   adapter.configure(config);
 *   ModelResponse response = adapter.chat(request);
 */
@Slf4j
@Component
public class ModelAdapterFactory {

    /** 适配器注册表：provider → adapter 实例 */
    private final Map<String, ModelAdapter> adapterMap = new ConcurrentHashMap<>();

    /** Spring 应用上下文——用于扫描 Bean */
    private final ApplicationContext context;

    public ModelAdapterFactory(ApplicationContext context) {
        this.context = context;
    }

    // ========== 初始化 ==========

    /**
     * 应用启动后自动扫描并注册所有 ModelAdapter 实现
     * 无需手动注册，新增适配器只需加 @Component 即可
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
            log.info("注册适配器: [{}] → {}", provider, adapter.getClass().getSimpleName());
        }

        log.info("适配器工厂初始化完成，共注册 {} 个适配器: {}", adapterMap.size(), adapterMap.keySet());
    }

    // ========== 获取适配器 ==========

    /**
     * 根据厂商标识获取适配器
     *
     * @param provider 厂商名，如 "minimax"、"deepseek-openai"
     * @return 对应的适配器实例
     * @throws ModelException 如果找不到对应的适配器
     */
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

    /**
     * 根据模型配置获取并配置适配器
     * 一步完成获取 + 配置，是最常用的方法
     *
     * @param config 模型配置（从存储层读取）
     * @return 已配置好的适配器
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
     * 获取适配器（安全版本，不存在时返回 Optional.empty）
     */
    public Optional<ModelAdapter> findAdapter(String provider) {
        return Optional.ofNullable(adapterMap.get(provider));
    }

    // ========== 查询 ==========

    /**
     * 列出所有已注册的厂商标识
     */
    public Set<String> listProviders() {
        return Collections.unmodifiableSet(adapterMap.keySet());
    }

    /**
     * 检查是否注册了某个厂商的适配器
     */
    public boolean hasProvider(String provider) {
        return adapterMap.containsKey(provider);
    }

    /**
     * 获取已注册的适配器数量
     */
    public int getAdapterCount() {
        return adapterMap.size();
    }

    // ========== 刷新 ==========

    /**
     * 重新扫描并注册适配器
     * 用于热更新场景（新增了适配器 jar 包）
     */
    public void refresh() {
        adapterMap.clear();
        init();
        log.info("适配器工厂已刷新");
    }
}