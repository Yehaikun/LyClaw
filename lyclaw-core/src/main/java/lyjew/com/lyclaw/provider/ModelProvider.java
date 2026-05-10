package lyjew.com.lyclaw.provider;

import lyjew.com.lyclaw.adapter.ModelAdapter;

import java.util.Set;

/**
 * 模型适配器提供者 —— Engine↔Adapter 防腐层接口。
 *
 * <p>engine 层通过此接口获取模型适配器，而不直接依赖 lyclaw-adapter 模块的具体类。
 * 具体实现在 lyclaw-adapter 中由 Spring 注入。</p>
 *
 * <p><b>为什么要防腐层</b>：如果 engine 直接调用 ModelAdapterFactory，那么：
 * <ul>
 *   <li>engine 层在编译期就绑死了 adapter 模块</li>
 *   <li>未来替换适配器获取方式（如改为 gRPC，或从配置中心动态获取），engine 层必须改代码</li>
 *   <li>单元测试时难以 mock 适配器创建过程</li>
 * </ul>
 * 通过 ModelProvider 接口，engine 层只依赖一个简单的接口，获取方式的变化对 engine 完全透明。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>ToolCallLoopStage 调用模型时需要适配器</li>
 *   <li>DefaultEngine 初始化时通过 getConfiguredAdapter() 获取默认适配器</li>
 *   <li>ChatContext 中 TODO 占位的 ModelProvider 引用</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see lyjew.com.lyclaw.adapter.ModelAdapter
 */
public interface ModelProvider {

    /**
     * 按厂商名获取适配器。
     *
     * @param provider 厂商标识，如 "minimax"、"deepseek"
     * @return ModelAdapter 实例
     * @throws IllegalArgumentException 如果厂商名不存在
     */
    ModelAdapter getAdapter(String provider);

    /**
     * 获取默认厂商名。
     *
     * @return 默认厂商标识
     */
    String getDefaultProvider();

    /**
     * 获取已配置的默认适配器（等价于 getAdapter(getDefaultProvider())）。
     *
     * @return 默认 ModelAdapter 实例
     */
    ModelAdapter getConfiguredAdapter();

    /**
     * 列出所有可用厂商。
     *
     * @return 厂商名集合
     */
    Set<String> listProviders();

    /**
     * 刷新适配器列表。配置变更后调用，使新增的适配器生效。
     */
    void refresh();
}