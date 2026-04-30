package lyjew.com.lyclaw.provider;

import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.adapter.factory.ModelAdapterFactory;
import lyjew.com.lyclaw.provider.ModelProvider;

import java.util.Set;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * ModelProvider 的适配器实现 —— 将 ModelAdapterFactory 适配为 ModelProvider 接口。
 *
 * <p>加了 @Primary，Spring 在多个 ModelProvider 候选 Bean 中优先注入此实现。</p>
 *
 * <p><b>设计动机</b>：ModelProvider 接口定义在 lyclaw-engine 模块，
 * 但具体实现需要依赖 lyclaw-adapter 模块的 ModelAdapterFactory。
 * 如果 engine 模块直接依赖 adapter 模块，会导致模块间循环依赖。
 * 因此 ModelProvider 的实现放在 adapter 模块中，
 * engine 层只依赖接口（仓鼠层隔离模式）。</p>
 *
 * <p><b>工作流程</b>：
 * <ol>
 *   <li>ToolCallLoopStage 需要调用模型时，通过 ModelProvider.getConfiguredAdapter() 获取适配器</li>
 *   <li>ModelProviderImpl 从 ModelAdapterFactory 获取对应厂商的适配器</li>
 *   <li>ModelAdapterFactory.getConfiguredAdapter(ModelConfig) 需要 ModelConfig 参数</li>
 *   <li>ModelProviderImpl 不持有 ModelConfig，所以 getConfiguredAdapter() 委托给内部逻辑</li>
 * </ol>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ModelProvider
 * @see ModelAdapterFactory
 */
@Primary
@Component
public class ModelProviderImpl implements ModelProvider {

    private final ModelAdapterFactory adapterFactory;

    public ModelProviderImpl(ModelAdapterFactory adapterFactory) {
        this.adapterFactory = adapterFactory;
    }

    @Override
    public ModelAdapter getAdapter(String provider) {
        return adapterFactory.getAdapter(provider);
    }

    @Override
    public String getDefaultProvider() {
        return "deepseek-openai";
    }

    @Override
    public ModelAdapter getConfiguredAdapter() {
        return adapterFactory.getAdapter(getDefaultProvider());
    }

    @Override
    public Set<String> listProviders() {
        return adapterFactory.listProviders();
    }

    @Override
    public void refresh() {
        adapterFactory.refresh();
    }
}