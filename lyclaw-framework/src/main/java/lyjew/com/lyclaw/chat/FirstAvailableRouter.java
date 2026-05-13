package lyjew.com.lyclaw.chat;

import lyjew.com.lyclaw.model.ChatRequest;

import java.util.List;
import java.util.Map;

/**
 * 默认兜底路由策略（First Available Router），按 Provider 的注册顺序返回第一个可用的模型。
 *
 * <p>本类是 LyClaw 框架中 ModelRouter 接口的最简单实现，作为当应用未显式配置自定义
 * 路由策略 Bean 时的默认兜底方案。其路由逻辑极其简单直接：遍历 ChatModelRegistry 中
 * 所有已注册的 Provider 及其模型列表，返回找到的第一个非空 Provider 的第一个模型。
 * 不进行任何请求复杂度分析、负载均衡计算或成本优化决策，因此不具备真正意义上的
 * "智能路由"能力，仅作为框架的最低保障，确保在没有配置任何路由策略时系统仍能正常
 * 工作而不抛出异常。
 *
 * <p>路由策略的执行流程：获取 ChatModelRegistry 中所有 Provider 到模型列表的映射
 * （Map&lt;String, List&lt;ChatModel&gt;&gt;），按 Provider 的注册顺序遍历。对于每个 Provider，
 * 检查其模型列表是否非空，如果非空则取第一个模型并返回 RoutingDecision，决策信息包括
 * Provider 名称、模型名称、路由层级固定为 STANDARD、决策原因标记为 "first_available"。
 * 如果所有 Provider 的模型列表均为空（即没有任何可用的 AI 模型），则抛出
 * IllegalStateException，提示需要配置至少一个 AI 模型 Provider。
 *
 * <p>应用场景和限制：
 * <ul>
 *   <li>适用于开发和测试阶段的快速启动，无需关心路由配置</li>
 *   <li>适用于只有单一 AI Provider 的简单部署场景</li>
 *   <li>不适用于多 Provider、多模型的复杂生产环境，此时应实现自定义的 ModelRouter
 *       （如基于请求复杂度的智能路由器、基于负载均衡的动态路由器等）</li>
 *   <li>不考虑请求内容和上下文（context 参数被忽略），所有请求一律路由到同一模型</li>
 *   <li>路由层级固定为 STANDARD，不会根据请求特征动态调整</li>
 * </ul>
 *
 * @see ModelRouter
 * @see RoutingDecision
 * @see RoutingTier
 * @see ChatModelRegistry
 */
public class FirstAvailableRouter implements ModelRouter {

    private final ChatModelRegistry registry;

    public FirstAvailableRouter(ChatModelRegistry registry) {
        this.registry = registry;
    }

    @Override
    public RoutingDecision route(ChatRequest request, Object context) {
        Map<String, List<ChatModel>> all = registry.getAll();
        for (Map.Entry<String, List<ChatModel>> entry : all.entrySet()) {
            List<ChatModel> models = entry.getValue();
            if (!models.isEmpty()) {
                ChatModel first = models.get(0);
                return RoutingDecision.to(entry.getKey(), first.model(),
                        RoutingTier.STANDARD, "first_available");
            }
        }
        throw new IllegalStateException("没有可用的 ChatModel。请配置至少一个 AI 模型 Provider。");
    }
}
