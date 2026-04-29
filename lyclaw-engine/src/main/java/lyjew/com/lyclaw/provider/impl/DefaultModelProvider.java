package lyjew.com.lyclaw.provider.impl;

import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.provider.ModelProvider;

import java.util.Collections;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * ModelProvider 的默认实现 —— 无 adapter 模块时的兜底。
 *
 * <p><b>设计动机</b>：ModelProvider 接口定义在 engine 层，但实现类应该在
 * lyclaw-adapter 模块中（依赖具体的 ModelAdapter）。然而如果 engine 层没有
 * 默认实现，Spring 启动时 ToolCallLoopStage 注入 ModelProvider 会报
 * "找不到 Bean" 错误。</p>
 *
 * <p>DefaultModelProvider 作为兜底实现，getConfiguredAdapter() 抛出清晰异常，
 * 提示用户需要在 adapter 模块提供有效的 ModelProvider 实现。
 * 当 adapter 模块的 ModelProvider 实现被 @Component 扫描到后，
 * DefaultModelProvider 不会被注入（Spring 多候选时需要 @Primary 解决）。
 * 建议在 adapter 模块的实现上加 @Primary。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ModelProvider
 */
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
        // 默认实现不做任何事
    }
}