package lyjew.com.lyclaw.chat.catalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 模型目录，持有所有 {@link ModelCatalogEntry} 实例。
 *
 * <p>目录在启动时构建，数据来源包括：
 * <ul>
 *   <li>静态配置（yml）</li>
 *   <li>标注了 {@code @ChatModel} 的 Bean（自动发现）</li>
 *   <li>{@code ProviderDiscovery} 响应</li>
 * </ul>
 *
 * <p>内部使用 {@link ConcurrentHashMap} 以规范 ID 为键存储条目，
 * 确保线程安全的读写。
 */
public class ModelCatalog {

    private final Map<String, ModelCatalogEntry> entries = new ConcurrentHashMap<>();

    /**
     * 注册一个模型条目。
     *
     * @param entry 模型条目
     */
    public void register(ModelCatalogEntry entry) {
        entries.put(entry.getId(), entry);
    }

    /**
     * 批量注册模型条目。
     *
     * @param entries 模型条目集合
     */
    public void registerAll(Collection<ModelCatalogEntry> entries) {
        for (ModelCatalogEntry entry : entries) {
            register(entry);
        }
    }

    /**
     * 通过规范 ID 查找模型条目。
     *
     * @param id 规范 ID，例如 "openai/gpt-4o"
     * @return 模型条目，如果未找到则为空
     */
    public Optional<ModelCatalogEntry> get(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    /**
     * 通过提供商和模型名称查找模型条目。
     *
     * @param provider 提供商
     * @param model    模型名称
     * @return 模型条目，如果未找到则为空
     */
    public Optional<ModelCatalogEntry> getByProvider(String provider, String model) {
        return get(ModelCatalogEntry.canonicalId(provider, model));
    }

    /**
     * 列出指定提供商的所有模型。
     *
     * @param provider 提供商
     * @return 该提供商的所有模型条目（包括不可用和已弃用的）
     */
    public List<ModelCatalogEntry> listByProvider(String provider) {
        return entries.values().stream()
                .filter(e -> e.getProvider().equalsIgnoreCase(provider))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 列出目录中的所有模型条目。
     *
     * @return 所有条目的不可变列表
     */
    public List<ModelCatalogEntry> listAll() {
        return List.copyOf(entries.values());
    }

    /**
     * 列出指定提供商当前可用的模型。
     *
     * @param provider 提供商
     * @return 该提供商当前可用的模型条目列表
     */
    public List<ModelCatalogEntry> listAvailable(String provider) {
        return entries.values().stream()
                .filter(e -> e.getProvider().equalsIgnoreCase(provider))
                .filter(ModelCatalogEntry::isAvailable)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 检查指定模型当前是否可用。
     *
     * @param provider 提供商
     * @param model    模型名称
     * @return true 如果模型存在且可用
     */
    public boolean isAvailable(String provider, String model) {
        return getByProvider(provider, model)
                .map(ModelCatalogEntry::isAvailable)
                .orElse(false);
    }

    /**
     * 更新指定模型的可用性状态。
     *
     * @param provider  提供商
     * @param model     模型名称
     * @param available 新的可用状态
     * @return true 如果模型存在且状态已更新，false 如果模型不存在
     */
    public boolean setAvailable(String provider, String model, boolean available) {
        return getByProvider(provider, model)
                .map(entry -> {
                    entry.setAvailable(available);
                    return true;
                })
                .orElse(false);
    }

    /**
     * 通过短别名解析模型条目。
     *
     * <p>遍历所有条目，查找 alias 字段匹配的条目。
     * 如果多个条目共享同一别名，返回第一个匹配的条目。
     *
     * @param alias 短别名，例如 "gpt4"
     * @return 模型条目，如果未找到则为空
     */
    public Optional<ModelCatalogEntry> resolveByAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return Optional.empty();
        }
        return entries.values().stream()
                .filter(e -> alias.equals(e.getAlias()))
                .findFirst();
    }

    /**
     * 返回目录中条目的总数。
     *
     * @return 条目数量
     */
    public int size() {
        return entries.size();
    }

    /**
     * 清空目录中的所有条目。
     */
    public void clear() {
        entries.clear();
    }

    @Override
    public String toString() {
        return "ModelCatalog{" +
                "size=" + entries.size() +
                ", entries=" + entries.keySet() +
                '}';
    }
}
